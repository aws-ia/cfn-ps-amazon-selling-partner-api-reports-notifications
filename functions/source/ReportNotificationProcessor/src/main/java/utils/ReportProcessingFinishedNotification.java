package utils;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReportProcessingFinishedNotification {

    @JsonProperty("sellerId")
    public String SellerId;

    @JsonProperty("reportId")
    public String ReportId;

    @JsonProperty("reportType")
    public String ReportType;

    @JsonProperty("processingStatus")
    public String ProcessingStatus;

    @JsonProperty("reportDocumentId")
    public String ReportDocumentId;
}
