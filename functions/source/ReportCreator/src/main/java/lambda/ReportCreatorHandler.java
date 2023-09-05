package lambda;

import com.amazon.SellingPartnerAPIAA.AWSAuthenticationCredentials;
import com.amazon.SellingPartnerAPIAA.AWSAuthenticationCredentialsProvider;
import com.amazon.SellingPartnerAPIAA.LWAAuthorizationCredentials;
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
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import io.swagger.client.api.ReportsApi;
import io.swagger.client.model.CreateReportSpecification;
import io.swagger.client.model.ReportOptions;
import org.threeten.bp.OffsetDateTime;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueResponse;
import utils.AppCredentials;
import utils.IAMUserCredentials;
import utils.RegionConfig;
import utils.ReportCreatorResponse;
import utils.ReportRequest;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static utils.Constants.LWA_ENDPOINT;
import static utils.Constants.VALID_SP_API_REGION_CONFIG;

public class ReportCreatorHandler implements RequestHandler<Map<String, String>, ReportCreatorResponse> {

    //Lambda Environment Variables
    private static final String IAM_USER_CREDENTIALS_SECRET_ARN_ENV_VARIABLE = "IAM_USER_CREDENTIALS_SECRET_ARN";
    private static final String SP_API_APP_CREDENTIALS_SECRET_ARN_ENV_VARIABLE = "SP_API_APP_CREDENTIALS_SECRET_ARN";
    private static final String ROLE_ARN_ENV_VARIABLE = "ROLE_ARN";
    private static final String ENCRYPTION_KEY_ARN_ENV_VARIABLE = "ENCRYPTION_KEY_ARN";
    private static final String SELLING_PARTNERS_TABLE_NAME_ENV_VARIABLE = "SELLING_PARTNERS_TABLE_NAME";
    private static final String REPORTS_TABLE_NAME_ENV_VARIABLE = "REPORTS_TABLE_NAME";

    //Lambda Input Parameters
    private static final String SELLER_ID_KEY_NAME = "SellerId";
    private static final String REGION_CODE_KEY_NAME = "RegionCode";
    private static final String REPORT_TYPE_KEY_NAME = "ReportType";
    private static final String MARKETPLACE_IDS_KEY_NAME = "MarketplaceIds";
    private static final String REPORT_DATA_START_TIME_KEY_NAME = "ReportDataStartTime";
    private static final String REPORT_DATA_END_TIME_KEY_NAME = "ReportDataEndTime";
    private static final String REPORT_OPTIONS_KEY_NAME = "ReportOptions";

    private static final String ROLE_SESSION_NAME = "report-creator-lambda-role-session";
    private static final String SELLING_PARTNERS_TABLE_HASH_KEY_NAME = "SellerId";
    private static final String SELLING_PARTNERS_TABLE_TOKEN_NAME = "RefreshToken";
    private static final String REPORTS_TABLE_HASH_KEY_NAME = "ReportId";
    private static final String REPORTS_TABLE_RANGE_KEY_NAME = "SellerId";
    private static final String REPORTS_TABLE_REGION_CODE_NAME = "RegionCode";

    @Override
    public ReportCreatorResponse handleRequest(Map<String, String> event, Context context) {
        LambdaLogger logger = context.getLogger();
        logger.log("ReportCreator Lambda handler started");

        validateInput(event);

        String sellerId = event.get(SELLER_ID_KEY_NAME);
        String regionCode = event.get(REGION_CODE_KEY_NAME);

        try {
            ReportRequest reportRequest = getReportRequest(event);

            String reportId = createReport(regionCode, sellerId, reportRequest);
            logger.log(String.format("Report creation submitted - Report Id: %s", reportId));

            storeReportData(reportId, sellerId, regionCode);

            return ReportCreatorResponse.builder()
                    .reportId(reportId)
                    .build();
        } catch (Exception e) {
            throw new InternalError("Create report failed", e);
        }
    }

    private ReportRequest getReportRequest(Map<String, String> event) throws Exception {
        ReportRequest reportRequest = new ReportRequest();
        reportRequest.setReportType(event.get(REPORT_TYPE_KEY_NAME));
        reportRequest.setMarketplaceIds(Arrays.asList(event.get(MARKETPLACE_IDS_KEY_NAME).split(",")));

        if (event.containsKey(REPORT_DATA_START_TIME_KEY_NAME) && event.containsKey(REPORT_DATA_END_TIME_KEY_NAME)) {
            OffsetDateTime dataStartTime = OffsetDateTime.parse(event.get(REPORT_DATA_START_TIME_KEY_NAME));
            OffsetDateTime dataEndTime = OffsetDateTime.parse(event.get(REPORT_DATA_END_TIME_KEY_NAME));

            reportRequest.setReportDataStartTime(dataStartTime);
            reportRequest.setReportDataEndTime(dataEndTime);
        }

        if (event.containsKey(REPORT_OPTIONS_KEY_NAME)) {
            String reportOptionsStr = event.get(REPORT_OPTIONS_KEY_NAME);

            if (!reportOptionsStr.isEmpty()) {
                ObjectMapper mapper = new ObjectMapper();
                Map<String, String> reportOptionsMap = mapper.readValue(reportOptionsStr, HashMap.class);

                ReportOptions reportOptions = new ReportOptions();
                for (Map.Entry<String, String> entry : reportOptionsMap.entrySet()) {
                    reportOptions.put(entry.getKey(), entry.getValue());
                }

                reportRequest.setReportOptions(reportOptions);
            }
        }

        return reportRequest;
    }

    private String createReport(String regionCode, String sellerId, ReportRequest reportRequest) throws Exception {
        CreateReportSpecification request = new CreateReportSpecification();
        request.setReportType(reportRequest.getReportType());
        request.setMarketplaceIds(reportRequest.getMarketplaceIds());
        request.setDataStartTime(reportRequest.getReportDataStartTime());
        request.setDataEndTime(reportRequest.getReportDataEndTime());

        request.setReportOptions(reportRequest.getReportOptions());

        ReportsApi reportsApi = getReportsApi(regionCode, sellerId);
        return reportsApi.createReport(request).getReportId();
    }

    private ReportsApi getReportsApi(String regionCode, String sellerId) throws Exception {
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

        String refreshToken = getRefreshToken(sellerId);

        LWAAuthorizationCredentials lwaAuthorizationCredentials = LWAAuthorizationCredentials.builder()
                .clientId(appCredentials.getClientId())
                .clientSecret(appCredentials.getClientSecret())
                .endpoint(LWA_ENDPOINT)
                .refreshToken(refreshToken)
                .build();

        return new ReportsApi.Builder()
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

    private void storeReportData(String reportId, String sellerId, String regionCode) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put(REPORTS_TABLE_HASH_KEY_NAME, new AttributeValue(reportId));
        item.put(REPORTS_TABLE_RANGE_KEY_NAME, new AttributeValue(sellerId));
        item.put(REPORTS_TABLE_REGION_CODE_NAME, new AttributeValue(regionCode));

        PutItemRequest putItemRequest = new PutItemRequest()
                .withTableName(System.getenv(REPORTS_TABLE_NAME_ENV_VARIABLE))
                .withItem(item);

        AmazonDynamoDB dynamoDB = AmazonDynamoDBClientBuilder.defaultClient();
        dynamoDB.putItem(putItemRequest);
    }

    private void validateInput(Map<String, String> event) {
        List<String> requiredParameters = Lists.newArrayList(
                REGION_CODE_KEY_NAME,
                SELLER_ID_KEY_NAME,
                REPORT_TYPE_KEY_NAME,
                MARKETPLACE_IDS_KEY_NAME);

        if (!event.keySet().containsAll(requiredParameters)) {
            String msg = String.format("The provided input must contain all the following keys: %s",
                    requiredParameters);

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
