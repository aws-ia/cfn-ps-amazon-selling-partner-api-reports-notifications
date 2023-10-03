package utils;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class NotificationsSubscriberResponse {

    public String destinationId;

    public String subscriptionId;
}
