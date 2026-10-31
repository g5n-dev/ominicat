<!-- ominicat-language-nav -->
[简体中文](docs/zh-CN/SECURITY.md) · **English**
<!-- /ominicat-language-nav -->

# Security

Report a suspected vulnerability privately through GitHub's security advisory feature when available. Avoid putting credentials or conversation payloads in public issues.

The standalone server binds to loopback by default. Before exposing it, configure `tokenEnv`, terminate TLS at a trusted proxy and apply deployment-level quotas. Health and the static playground are public; metrics and WebSocket access honor configured authentication. The browser transport uses a subprotocol for its token so credentials do not enter URL query strings.

Provider endpoints require HTTPS except for loopback fixtures. Redirects are disabled; provider response bodies and credentials are omitted from transport error messages. Configure finite timeouts and size limits. Plugins execute trusted Java code in the host process; classpath discovery is not a security boundary.

Conversation state is transient. Persistence, authorization between sessions, secret rotation and tenant isolation beyond per-session processor ownership remain application responsibilities. A generated dependency SBOM is an inventory, not a vulnerability scan or certification.
