package utils;

import com.google.common.collect.Lists;

import java.util.List;

public class Constants {

    public static final String NOTIFICATION_TYPE_REPORT_PROCESSING_FINISHED = "REPORT_PROCESSING_FINISHED";

    public static final String REPORT_PROCESSING_STATUS_DONE = "DONE";
    public static final String REPORT_PROCESSING_STATUS_CANCELLED = "CANCELLED";
    public static final String REPORT_PROCESSING_STATUS_FATAL = "FATAL";

    public static final List<String> REPORT_PROCESSING_FINAL_STATUSES = Lists.newArrayList(
            REPORT_PROCESSING_STATUS_DONE,
            REPORT_PROCESSING_STATUS_CANCELLED,
            REPORT_PROCESSING_STATUS_FATAL);
}
