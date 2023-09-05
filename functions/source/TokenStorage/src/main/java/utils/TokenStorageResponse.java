package utils;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class TokenStorageResponse {

    public String sellerId;

    public String status;
}
