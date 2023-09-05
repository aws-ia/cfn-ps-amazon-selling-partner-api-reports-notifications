package utils;

import com.google.common.collect.ImmutableMap;

import java.util.Map;

public class Constants {

    public static final String COMPRESSION_GZIP_KEY_NAME = "GZIP";
    public static final String COMPRESSION_GZIP_METADATA_VALUE = "application/x-gzip";

    public static final Map<String, String> COMPRESSION_METADATA_MAP = ImmutableMap.of(
            COMPRESSION_GZIP_KEY_NAME, COMPRESSION_GZIP_METADATA_VALUE);
}
