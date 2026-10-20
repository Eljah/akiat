package io.github.eljah.akiat.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/** Private MCP bearer token, separate from the editor's HTTP Basic password. */
public class McpBearerFilter extends OncePerRequestFilter {
    private final String token;
    public McpBearerFilter(String token) { this.token=token; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException,IOException {
        String auth=request.getHeader("Authorization");
        if(token!=null && !token.isBlank()
                && (request.getServletPath().equals("/mcp") || request.getServletPath().startsWith("/mcp/"))
                && auth!=null && auth.startsWith("Bearer ")) {
            byte[] offered=auth.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8);
            byte[] expected=token.getBytes(StandardCharsets.UTF_8);
            if(MessageDigest.isEqual(offered,expected)) {
                SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken("akiat-mcp",null,
                        List.of(new SimpleGrantedAuthority("ROLE_EDITOR"))));
            }
        }
        chain.doFilter(request,response);
    }
}
