package me.golemcore.bot.port.outbound;

import java.net.URI;

public interface ModelRegistryRemotePort {

    String fetchText(URI uri);
}
