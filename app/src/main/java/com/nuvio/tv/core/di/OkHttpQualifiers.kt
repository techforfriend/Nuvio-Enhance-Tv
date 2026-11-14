package com.nuvio.tv.core.di

import javax.inject.Qualifier

/**
 * Binary-retention DI qualifiers for every OkHttpClient binding (research
 * spec W2.1). String [javax.inject.Named] is not retained in bytecode; custom
 * qualifiers are, so R8-full-mode and DI tooling cannot mismatch a client
 * binding by name. There is deliberately NO unqualified OkHttpClient binding.
 *
 * Playback clients deliberately have NO qualifier or binding here: the playback
 * lineage (root client, trust-all fallback, prewarm, per-source auth variants)
 * lives as object singletons in PlayerPlaybackNetworking because Media3 data
 * sources share one ConnectionPool and their lifetime is player-scoped, not
 * DI-scoped; StreamSweepEngine derives its h1.1 A/B client inline from the root
 * via newBuilder(). That lineage already satisfies the W2.1 playback contract:
 * no callTimeout (spans body reads), no logging, no OkHttp cache, only
 * functional interceptors (SSL fallback, per-source auth).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Api

/**
 * AuthManager's raw Supabase auth calls (password sign-in, token refresh, TV
 * login) against the configured backend, default or custom: validated TLS.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BackendAuth

/** TLS-validating client for fixed commercial endpoints (main review F20). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Validated

/** Coil's callFactory client: short timeouts, own dispatcher, no OkHttp cache. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Image

/**
 * Streaming downloads from untrusted hosts (subtitles): no
 * callTimeout (spans body reads), no JSON gate, no logging — consumers
 * stream to disk/file, so there is no heap-buffering vector to cap here
 * (W2.2 note: heap-buffering downloads belong on this client, never on
 * [Api], whose response guard caps bodies at 2 MB). The same TLS as
 * [Api] (Spec 68 X7: validated, plus certificates the user trusted for an addon).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class Download
