package com.urbancompany.platform.notification;

import com.urbancompany.platform.auth.AuthPrincipal;
import com.urbancompany.platform.auth.JwtService;
import com.urbancompany.platform.auth.Role;
import com.urbancompany.platform.booking.BookingAccessService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** JWT auth on STOMP CONNECT + per-booking authorization on SUBSCRIBE (only customer, assigned partner or admin). */
@Component
@RequiredArgsConstructor
public class StompAuthInterceptor implements ChannelInterceptor {
    private static final Pattern BOOKING_TOPIC = Pattern.compile("^/topic/bookings/(\\d+)/.*");
    private final JwtService jwt;
    private final BookingAccessService access;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor acc = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (acc == null || acc.getCommand() == null) return message;
        if (StompCommand.CONNECT.equals(acc.getCommand())) {
            String h = acc.getFirstNativeHeader("Authorization");
            if (h == null || !h.startsWith("Bearer ")) throw new MessagingException("Missing bearer token");
            try {
                Claims c = jwt.parse(h.substring(7));
                if (!"access".equals(c.get("typ"))) throw new MessagingException("Invalid token type");
                Role role = Role.valueOf((String) c.get("role"));
                acc.setUser(new UsernamePasswordAuthenticationToken(new AuthPrincipal(Long.valueOf(c.getSubject()), role), null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
            } catch (MessagingException e) { throw e; }
            catch (Exception e) { throw new MessagingException("Invalid token"); }
        } else if (StompCommand.SUBSCRIBE.equals(acc.getCommand())) {
            if (!(acc.getUser() instanceof Authentication a) || !(a.getPrincipal() instanceof AuthPrincipal p)) throw new MessagingException("Not authenticated");
            String dest = acc.getDestination();
            if (dest == null) return message;
            Matcher m = BOOKING_TOPIC.matcher(dest);
            if (m.matches()) { if (!access.canAccess(p.userId(), p.role(), Long.valueOf(m.group(1)))) throw new MessagingException("Not allowed to subscribe to this booking"); }
            else if (dest.startsWith("/topic/")) throw new MessagingException("Unknown topic");
        } else if (StompCommand.SEND.equals(acc.getCommand()) && acc.getUser() == null) throw new MessagingException("Not authenticated");
        return message;
    }
}
