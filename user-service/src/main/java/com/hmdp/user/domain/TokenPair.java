package com.hmdp.user.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenPair {

    private String accessToken;

    private String refreshToken;

    private Integer tokenVersion;
}
