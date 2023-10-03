package lambda;

import com.amazon.SellingPartnerAPIAA.AWSAuthenticationCredentials;
import com.amazon.SellingPartnerAPIAA.AWSAuthenticationCredentialsProvider;
import com.amazon.SellingPartnerAPIAA.LWAAuthorizationCredentials;
import com.amazon.SellingPartnerAPIAA.LWAClientScopes;
import com.amazonaws.encryptionsdk.AwsCrypto;
import com.amazonaws.encryptionsdk.CommitmentPolicy;
import com.amazonaws.encryptionsdk.CryptoResult;
import com.amazonaws.encryptionsdk.kms.KmsMasterKey;
import com.amazonaws.encryptionsdk.kms.KmsMasterKeyProvider;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.GetItemRequest;
import com.amazonaws.services.dynamodbv2.model.GetItemResult;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import io.swagger.client.ApiException;
import io.swagger.client.api.NotificationsApi;
import io.swagger.client.model.CreateDestinationRequest;
import io.swagger.client.model.CreateDestinationResponse;
import io.swagger.client.model.CreateSubscriptionRequest;
import io.swagger.client.model.CreateSubscriptionResponse;
import io.swagger.client.model.Destination;
import io.swagger.client.model.DestinationResourceSpecification;
import io.swagger.client.model.GetDestinationsResponse;
import io.swagger.client.model.GetSubscriptionResponse;
import io.swagger.client.model.SqsResource;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import utils.AppCredentials;
import utils.IAMUserCredentials;
import utils.NotificationsSubscriberResponse;
import utils.RegionConfig;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static utils.Constants.LWA_ENDPOINT;
import static utils.Constants.VALID_SP_API_REGION_CONFIG;
import static utils.Constants.VALID_SQS_NOTIFICATION_TYPES;

public class NotificationsSubscriberHandler implements RequestHandler<Map<String, String>, NotificationsSubscriberResponse> {

    //Lambda Environment Variables
    private static final String IAM_USER_CREDENTIALS_SECRET_ARN_ENV_VARIABLE = "IAM_USER_CREDENTIALS_SECRET_ARN";
    private static final String SP_API_APP_CREDENTIALS_SECRET_ARN_ENV_VARIABLE = "SP_API_APP_CREDENTIALS_SECRET_ARN";
    private static final String ROLE_ARN_ENV_VARIABLE = "ROLE_ARN";
    private static final String ENCRYPTION_KEY_ARN_ENV_VARIABLE = "ENCRYPTION_KEY_ARN";
    private static final String SELLING_PARTNERS_TABLE_NAME_ENV_VARIABLE = "SELLING_PARTNERS_TABLE_NAME";
    private static final String SQS_QUEUE_ARN_ENV_VARIABLE = "SQS_QUEUE_ARN";

    //Lambda Input Parameters
    private static final String SELLER_ID_KEY_NAME = "SellerId";
    private static final String REGION_CODE_KEY_NAME = "RegionCode";
    private static final String NOTIFICATION_TYPE_KEY_NAME = "NotificationType";

    private static final String ROLE_SESSION_NAME = "notifications-subscriber-lambda-role-session";
    private static final String LWA_NOTIFICATIONS_SCOPE = "sellingpartnerapi::notifications";
    private static final String NOTIFICATION_PAYLOAD_VERSION = "1.0";
    private static final String SELLING_PARTNERS_TABLE_HASH_KEY_NAME = "SellerId";
    private static final String SELLING_PARTNERS_TABLE_TOKEN_NAME = "RefreshToken";
    private static final int DESTINATION_EXISTS_STATUS_CODE = 409;
    private static final int SUBSCRIPTION_EXISTS_STATUS_CODE = 409;

    @Override
    public NotificationsSubscriberResponse handleRequest(Map<String, String> event, Context context) {
        LambdaLogger logger = context.getLogger();
        logger.log("NotificationsSubscriber Lambda handler started");

        validateInput(event);

        String sellerId = event.get(SELLER_ID_KEY_NAME);
        String regionCode = event.get(REGION_CODE_KEY_NAME);
        String notificationType = event.get(NOTIFICATION_TYPE_KEY_NAME);

        validateNotificationType(notificationType);

        String destinationId = "";
        try {
            destinationId = createDestination(regionCode);
            logger.log(String.format("Destination created - Destination Id: %s", destinationId));
        } catch (Exception e) {
            throw new InternalError("Create destination failed", e);
        }

        try {
            String refreshToken = getRefreshToken(sellerId);

            String subscriptionId = createSubscription(regionCode, refreshToken, notificationType, destinationId);
            logger.log(String.format("Subscription created - Subscription Id: %s", subscriptionId));

            return NotificationsSubscriberResponse.builder()
                    .destinationId(destinationId)
                    .subscriptionId(subscriptionId)
                    .build();
        } catch (Exception e) {
            throw new InternalError("Create subscription failed", e);
        }
    }

    private String createDestination(String regionCode) throws Exception {
        String sqsQueueArn = System.getenv(SQS_QUEUE_ARN_ENV_VARIABLE);

        SqsResource sqsResource = new SqsResource();
        sqsResource.setArn(sqsQueueArn);

        DestinationResourceSpecification resourceSpec = new DestinationResourceSpecification();
        resourceSpec.setSqs(sqsResource);

        CreateDestinationRequest request = new CreateDestinationRequest();
        request.setName(UUID.randomUUID().toString());
        request.setResourceSpecification(resourceSpec);

        NotificationsApi notificationsApi = getNotificationsApi(regionCode, null, true);

        String destinationId = "";
        try {
            CreateDestinationResponse createDestinationResponse = notificationsApi.createDestination(request);
            destinationId = createDestinationResponse.getPayload().getDestinationId();
        } catch (ApiException e) {
            if (e.getCode() == DESTINATION_EXISTS_STATUS_CODE) {
                GetDestinationsResponse getDestinationsResponse = notificationsApi.getDestinations();

                Destination sqsDestination = getDestinationsResponse.getPayload().stream()
                        .filter(destination -> destination.getResource().getSqs() != null)
                        .filter(destination -> sqsQueueArn.equals(destination.getResource().getSqs().getArn()))
                        .findFirst()
                        .get();

                destinationId = sqsDestination.getDestinationId();
            } else {
                throw e;
            }
        }

        return destinationId;
    }

    private String createSubscription(String regionCode, String refreshToken, String notificationType, String destinationId)
            throws Exception {

        CreateSubscriptionRequest request = new CreateSubscriptionRequest();
        request.setDestinationId(destinationId);
        request.setPayloadVersion(NOTIFICATION_PAYLOAD_VERSION);

        NotificationsApi notificationsApi = getNotificationsApi(regionCode, refreshToken, false);

        String subscriptionId = "";
        try {
            CreateSubscriptionResponse response = notificationsApi.createSubscription(request, notificationType);
            subscriptionId = response.getPayload().getSubscriptionId();
        } catch (ApiException e) {
            if (e.getCode() == SUBSCRIPTION_EXISTS_STATUS_CODE) {
                GetSubscriptionResponse getSubscriptionResponse = notificationsApi.getSubscription(notificationType);
                subscriptionId = getSubscriptionResponse.getPayload().getSubscriptionId();
            } else {
                throw e;
            }
        }

        return subscriptionId;
    }

    private NotificationsApi getNotificationsApi(String regionCode, String refreshToken, boolean isGrantlessOperation)
            throws Exception {

        RegionConfig regionConfig = getRegionConfig(regionCode);

        ObjectMapper mapper = new ObjectMapper();

        String iamUserCredentialsSecret = getSecretString(System.getenv(IAM_USER_CREDENTIALS_SECRET_ARN_ENV_VARIABLE));
        IAMUserCredentials iamUserCredentials = mapper.readValue(iamUserCredentialsSecret, IAMUserCredentials.class);

        AWSAuthenticationCredentials awsAuthenticationCredentials=AWSAuthenticationCredentials.builder()
                .accessKeyId(iamUserCredentials.getAccessKeyId())
                .secretKey(iamUserCredentials.getSecretKey())
                .region(regionConfig.getAwsRegion())
                .build();

        AWSAuthenticationCredentialsProvider awsAuthenticationCredentialsProvider=AWSAuthenticationCredentialsProvider.builder()
                .roleArn(System.getenv(ROLE_ARN_ENV_VARIABLE))
                .roleSessionName(ROLE_SESSION_NAME)
                .build();

        String appCredentialsSecret = getSecretString(System.getenv(SP_API_APP_CREDENTIALS_SECRET_ARN_ENV_VARIABLE));
        AppCredentials appCredentials = mapper.readValue(appCredentialsSecret, AppCredentials.class);

        LWAAuthorizationCredentials lwaAuthorizationCredentials = LWAAuthorizationCredentials.builder()
                .clientId(appCredentials.getClientId())
                .clientSecret(appCredentials.getClientSecret())
                .endpoint(LWA_ENDPOINT)
                .build();

        if (isGrantlessOperation) {
            Set<String> scopesSet = new HashSet<>();
            scopesSet.add(LWA_NOTIFICATIONS_SCOPE);
            lwaAuthorizationCredentials.setScopes(new LWAClientScopes(scopesSet));
        } else {
            lwaAuthorizationCredentials.setRefreshToken(refreshToken);
        }

        return new NotificationsApi.Builder()
                .awsAuthenticationCredentials(awsAuthenticationCredentials)
                .lwaAuthorizationCredentials(lwaAuthorizationCredentials)
                .awsAuthenticationCredentialsProvider(awsAuthenticationCredentialsProvider)
                .endpoint(regionConfig.getSpApiEndpoint())
                .build();
    }

    private String getRefreshToken(String sellerId) {
        Map<String, AttributeValue> key = new HashMap<>();
        key.put(SELLING_PARTNERS_TABLE_HASH_KEY_NAME, new AttributeValue(sellerId));

        GetItemRequest getItemRequest = new GetItemRequest()
                .withTableName(System.getenv(SELLING_PARTNERS_TABLE_NAME_ENV_VARIABLE))
                .withKey(key);

        AmazonDynamoDB dynamoDB = AmazonDynamoDBClientBuilder.defaultClient();
        GetItemResult getItemResult = dynamoDB.getItem(getItemRequest);
        Map<String, AttributeValue> item = getItemResult.getItem();
        ByteBuffer encryptedRefreshToken = item.get(SELLING_PARTNERS_TABLE_TOKEN_NAME).getB();
        byte[] encryptedRefreshTokenBytes = encryptedRefreshToken.array();

        AwsCrypto crypto = AwsCrypto.builder()
                .withCommitmentPolicy(CommitmentPolicy.RequireEncryptRequireDecrypt)
                .build();

        KmsMasterKeyProvider keyProvider = KmsMasterKeyProvider.builder().buildStrict(System.getenv(ENCRYPTION_KEY_ARN_ENV_VARIABLE));

        CryptoResult<byte[], KmsMasterKey> decryptedData = crypto.decryptData(keyProvider, encryptedRefreshTokenBytes);
        return new String(decryptedData.getResult());
    }

    private void validateInput(Map<String, String> event) {
        List<String> requiredParameters = Lists.newArrayList(
                SELLER_ID_KEY_NAME,
                REGION_CODE_KEY_NAME,
                NOTIFICATION_TYPE_KEY_NAME);

        if (!event.keySet().containsAll(requiredParameters)) {
            String msg = String.format("The provided input must contain all the following keys: %s",
                    requiredParameters);

            throw new IllegalArgumentException(msg);
        }
    }

    private void validateNotificationType(String notificationType) {
        if (!VALID_SQS_NOTIFICATION_TYPES.contains(notificationType)) {
            String msg = String.format("Notification Type %s is not valid. Value must be one of %s",
                    notificationType,
                    VALID_SQS_NOTIFICATION_TYPES);

            throw new IllegalArgumentException(msg);
        }
    }

    private String getSecretString(String secretId) {
        SecretsManagerClient client = SecretsManagerClient.builder().build();
        GetSecretValueRequest request = GetSecretValueRequest.builder()
                .secretId(secretId)
                .build();

        GetSecretValueResponse response = client.getSecretValue(request);
        return response.secretString();
    }

    private RegionConfig getRegionConfig(String regionCode) {
        if (!VALID_SP_API_REGION_CONFIG.containsKey(regionCode)) {
            String msg = String.format("Region Code %s is not valid. Value must be one of %s",
                    regionCode,
                    VALID_SP_API_REGION_CONFIG.keySet());

            throw new IllegalArgumentException(msg);
        }

        return VALID_SP_API_REGION_CONFIG.get(regionCode);
    }
}
