package org.matrix.vector.ipc;

import org.matrix.vector.ipc.IFrameworkService;

/**
 * The one handshake system_server gets, and it gets it early.
 *
 * <p>system_server cannot use {@link IVectorDaemon}: it is specialized before the daemon has any
 * way to push a binder into it. Instead the daemon claims a system service name in servicemanager
 * before the real service registers, and system_server finds that proxy during specialization. Once
 * the real service turns up the proxy forwards everything to it, so this is a single opportunity
 * rather than a standing channel - anything system_server needs to hand the daemon has to be handed
 * over here.</p>
 */
interface ISystemServerBootstrap {
    /** As {@link IVectorDaemon#attachProcess}; only uid 1000 / "system" is accepted. */
    IFrameworkService attachProcess(int uid, int pid, String processName, IBinder processLifeToken);
}
