package utils;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReportDocumentResponse {

    public String url;

    public String compressionAlgorithm;
}
