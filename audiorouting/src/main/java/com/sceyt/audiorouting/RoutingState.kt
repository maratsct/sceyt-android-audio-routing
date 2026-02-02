package com.sceyt.audiorouting

/**
 * Represents the current state of the audio router.
 */
enum class RoutingState {
    /**
     * The router is stopped and not listening for device changes.
     * This is the initial state.
     */
    STOPPED,

    /**
     * The router is started and listening for device changes,
     * but audio routing is not yet activated.
     */
    STARTED,

    /**
     * The router is fully activated with audio focus acquired
     * and audio being routed to the selected device.
     */
    ACTIVATED
}
