package utils;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

@Builder
public class StateMachineInput {

    @JsonProperty("SellerId")
    public String SellerId;

    @JsonProperty("ReportId")
    public String ReportId;

    @JsonProperty("ReportType")
    public String ReportType;

    @JsonProperty("ProcessingStatus")
    public String ProcessingStatus;

    @JsonProperty("ReportDocumentId")
    public String ReportDocumentId;
}
