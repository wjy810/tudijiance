package cn.tudijiance.security;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import cn.tudijiance.common.AppProperties;
import cn.tudijiance.user.User;

@Service
public class JwtService {

    private final JwtEncoder encoder;
    private final AppProperties properties;
    private final Clock clock;

    public JwtService(JwtEncoder encoder, AppProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedToken issue(User user) {
        return issue(user, false);
    }

    /** @param mobile 手机端登录，令牌有效期用 app.jwt.mobile-ttl */
    public IssuedToken issue(User user, boolean mobile) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.jwt().ttlFor(mobile));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("tudijiance")
                .issuedAt(now)
                .expiresAt(expiresAt)
                .subject(user.getUsername())
                .claim("uid", user.getId())
                .claim("roles", List.of(user.getRole()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }
}
