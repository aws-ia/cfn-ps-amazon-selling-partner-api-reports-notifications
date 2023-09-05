package lambda;

import com.amazonaws.encryptionsdk.AwsCrypto;
import com.amazonaws.encryptionsdk.CommitmentPolicy;
import com.amazonaws.encryptionsdk.CryptoResult;
import com.amazonaws.encryptionsdk.kms.KmsMasterKey;
import com.amazonaws.encryptionsdk.kms.KmsMasterKeyProvider;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.google.common.collect.Lists;
import utils.TokenStorageResponse;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TokenStorageHandler implements RequestHandler<Map<String, String>, TokenStorageResponse> {

    //Lambda Environment Variables
    private static final String ENCRYPTION_KEY_ARN_ENV_VARIABLE = "ENCRYPTION_KEY_ARN";
    private static final String SELLING_PARTNERS_TABLE_NAME_ENV_VARIABLE = "SELLING_PARTNERS_TABLE_NAME";

    //Lambda Input Parameters
    private static final String SELLER_ID_KEY_NAME = "SellerId";
    private static final String REFRESH_TOKEN_KEY_NAME = "RefreshToken";

    private static final String SELLING_PARTNERS_TABLE_HASH_KEY_NAME = "SellerId";
    private static final String SELLING_PARTNERS_TABLE_TOKEN_NAME = "RefreshToken";

    @Override
    public TokenStorageResponse handleRequest(Map<String, String> event, Context context) {
        LambdaLogger logger = context.getLogger();
        logger.log("TokenStorage Lambda handler started");

        validateInput(event);

        String sellerId = event.get(SELLER_ID_KEY_NAME);
        String refreshToken = event.get(REFRESH_TOKEN_KEY_NAME);

        storeRefreshToken(sellerId, refreshToken);
        logger.log("TokenStorage Lambda succeeded");

        return TokenStorageResponse.builder()
                .sellerId(sellerId)
                .status("SUCCESS")
                .build();
    }

    private void validateInput(Map<String, String> event) {
        List<String> requiredParameters = Lists.newArrayList(
                SELLER_ID_KEY_NAME,
                REFRESH_TOKEN_KEY_NAME);

        if (!event.keySet().containsAll(requiredParameters)) {
            String msg = String.format("The provided input must contain all the following keys: %s",
                    requiredParameters);

            throw new IllegalArgumentException(msg);
        }
    }

    private void storeRefreshToken(String sellerId, String refreshToken) {
        AwsCrypto crypto = AwsCrypto.builder()
                .withCommitmentPolicy(CommitmentPolicy.RequireEncryptRequireDecrypt)
                .build();

        KmsMasterKeyProvider keyProvider = KmsMasterKeyProvider.builder().buildStrict(System.getenv(ENCRYPTION_KEY_ARN_ENV_VARIABLE));

        byte[] decryptedBytes = refreshToken.getBytes(StandardCharsets.UTF_8);
        CryptoResult<byte[], KmsMasterKey> encryptedData = crypto.encryptData(keyProvider, decryptedBytes);
        byte[] encryptedBytes = encryptedData.getResult();
        ByteBuffer buffer = ByteBuffer.wrap(encryptedBytes);

        Map<String, AttributeValue> item = new HashMap<>();
        item.put(SELLING_PARTNERS_TABLE_HASH_KEY_NAME, new AttributeValue(sellerId));
        item.put(SELLING_PARTNERS_TABLE_TOKEN_NAME, new AttributeValue().withB(buffer));

        PutItemRequest putItemRequest = new PutItemRequest()
                .withTableName(System.getenv(SELLING_PARTNERS_TABLE_NAME_ENV_VARIABLE))
                .withItem(item);

        AmazonDynamoDB dynamoDB = AmazonDynamoDBClientBuilder.defaultClient();
        dynamoDB.putItem(putItemRequest);
    }
}
