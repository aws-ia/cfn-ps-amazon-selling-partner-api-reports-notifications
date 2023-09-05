package utils;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Date;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SPAPINotification {

    @JsonProperty("notificationType")
    public String NotificationType;

    @JsonProperty("eventTime")
    public Date EventTime;

    @JsonProperty("payload")
    public NotificationPayload Payload;

    @JsonProperty("notificationMetadata")
    public NotificationMetadata NotificationMetadata;
}
