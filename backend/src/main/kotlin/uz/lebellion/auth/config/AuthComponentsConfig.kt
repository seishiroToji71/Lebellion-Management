package uz.lebellion.auth.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import uz.lebellion.auth.token.HmacCodec
import java.time.Clock

/**
 * Beans whose construction needs configuration values (secrets, clock).
 * Two HmacCodec instances with distinct secrets — inject by qualifier downstream.
 */
@Configuration
class AuthComponentsConfig {

    @Bean
    fun inviteHmacCodec(props: AuthProperties): HmacCodec = HmacCodec(props.invite.hmacSecret)

    @Bean
    fun passwordResetHmacCodec(props: AuthProperties): HmacCodec = HmacCodec(props.passwordReset.hmacSecret)

    @Bean
    fun authClock(): Clock = Clock.systemUTC()
}
