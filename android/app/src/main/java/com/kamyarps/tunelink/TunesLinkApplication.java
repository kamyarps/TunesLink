package com.kamyarps.tuneslink;

import android.app.Application;

/** Owns process-scoped connection state independently of Activity recreation. */
public final class TunesLinkApplication extends Application {
    private SessionGraph session;

    SessionGraph session() {
        if (session == null) session = new SessionGraph(this);
        return session;
    }

    static final class SessionGraph {
        final BridgeRepository repository;

        SessionGraph(Application application) {
            repository = new BridgeRepository(application);
        }
    }
}
