package utils;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import java.util.List;
import java.util.Map;

public class Constants {

    //Region Configuration
    public static final String NA_REGION_CODE = "NA";
    public static final String SP_API_NA_AWS_REGION = "us-east-1";
    public static final String SP_API_NA_ENDPOINT = "https://sellingpartnerapi-na.amazon.com";
    public static final String EU_REGION_CODE = "EU";
    public static final String SP_API_EU_AWS_REGION = "eu-west-1";
    public static final String SP_API_EU_ENDPOINT = "https://sellingpartnerapi-eu.amazon.com";
    public static final String FE_REGION_CODE = "FE";
    public static final String SP_API_FE_AWS_REGION = "us-west-2";
    public static final String SP_API_FE_ENDPOINT = "https://sellingpartnerapi-fe.amazon.com";

    public static final Map<String, RegionConfig> VALID_SP_API_REGION_CONFIG = ImmutableMap.of(
            NA_REGION_CODE, new RegionConfig(SP_API_NA_AWS_REGION, SP_API_NA_ENDPOINT),
            EU_REGION_CODE, new RegionConfig(SP_API_EU_AWS_REGION, SP_API_EU_ENDPOINT),
            FE_REGION_CODE, new RegionConfig(SP_API_FE_AWS_REGION, SP_API_FE_ENDPOINT));

    //Login With Amazon Configuration
    public static final String LWA_ENDPOINT = "https://api.amazon.com/auth/o2/token";

    //SQS Notification Types
    public static final List<String> VALID_SQS_NOTIFICATION_TYPES = Lists.newArrayList(
            "ACCOUNT_STATUS_CHANGED",
            "ANY_OFFER_CHANGED",
            "B2B_ANY_OFFER_CHANGED",
            "FBA_OUTBOUND_SHIPMENT_STATUS",
            "FEE_PROMOTION",
            "FEED_PROCESSING_FINISHED",
            "FULFILLMENT_ORDER_STATUS",
            "MFN_ORDER_STATUS_CHANGE",
            "REPORT_PROCESSING_FINISHED"
    );
}
