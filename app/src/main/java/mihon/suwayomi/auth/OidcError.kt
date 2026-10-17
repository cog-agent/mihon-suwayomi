package mihon.suwayomi.auth

/** Why a sign-in, refresh or verification step failed. Never carries token text or provider-supplied messages. */
enum class OidcError {
    NotConfigured,
    InvalidConfig,
    DiscoveryFailed,
    DiscoveryIssuerMismatch,
    DiscoveryInsecure,
    PkceUnsupported,
    Network,
    NoPendingRequest,
    StateMismatch,
    RequestExpired,
    AccessDenied,
    ProviderError,
    TokenExchangeFailed,
    InvalidToken,

    /** The ID token was signed by a key that is not in the (current) key set. */
    UnknownSigningKey,
    InsufficientScope,
    InvalidGrant,

    /** The server publishes no `/.well-known/suwayomi-client` document (or it is not reachable as one). */
    BootstrapUnavailable,

    /** The client-registration document is malformed, oversized, or advertises values this client must not trust. */
    BootstrapInvalid,
}

class OidcException(val error: OidcError, message: String = error.name, cause: Throwable? = null) :
    Exception(message, cause)
