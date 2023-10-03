package utils;

import io.swagger.client.model.ReportOptions;
import lombok.Data;
import org.threeten.bp.OffsetDateTime;

import java.util.List;
import java.util.Map;

@Data
public class ReportRequest {

    public String reportType;
    public List<String> marketplaceIds;
    public OffsetDateTime reportDataStartTime;
    public OffsetDateTime reportDataEndTime;
    public ReportOptions reportOptions;
}
