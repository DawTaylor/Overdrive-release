package com.overdrive.app.mqtt;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for {@link MqttConnectionConfig#getBrokerUri()} — in particular the protocol
 * inference for bare hostnames. TLS-only brokers (HiveMQ Cloud) drop plain MQTT sent to their
 * TLS port, surfacing as Paho reason 32109 / EOFException, so a bare host on 8883/8884 must be
 * routed through ssl:// / wss:// rather than tcp://. Also covers
 * {@link MqttConnectionConfig#normalizeBrokerUrl()}, which moves a port or path typed into the
 * broker URL into the port and path fields, and the optional WebSocket path field.
 */
public class MqttBrokerUriTest {

    private static MqttConnectionConfig config(String brokerUrl, int port) {
        MqttConnectionConfig c = new MqttConnectionConfig();
        c.brokerUrl = brokerUrl;
        c.port = port;
        return c;
    }

    private static MqttConnectionConfig config(String brokerUrl, int port, String path) {
        MqttConnectionConfig c = config(brokerUrl, port);
        c.path = path;
        return c;
    }

    @Test
    public void bareHostOnTlsPortInfersSsl() {
        MqttConnectionConfig c = config("abc123.s1.eu.hivemq.cloud", 8883);
        assertEquals("ssl://abc123.s1.eu.hivemq.cloud:8883", c.getBrokerUri());
        assertTrue(c.isSsl());
    }

    @Test
    public void bareHostOnSecureWebSocketPortInfersWss() {
        MqttConnectionConfig c = config("abc123.s1.eu.hivemq.cloud", 8884);
        assertEquals("wss://abc123.s1.eu.hivemq.cloud:8884/mqtt", c.getBrokerUri());
        assertTrue(c.isSsl());
    }

    @Test
    public void bareHostOnPlainPortStaysTcp() {
        MqttConnectionConfig c = config("192.168.1.10", 1883);
        assertEquals("tcp://192.168.1.10:1883", c.getBrokerUri());
        assertFalse(c.isSsl());
    }

    @Test
    public void normalizeMovesEmbeddedPortIntoPortField() {
        MqttConnectionConfig c = config("abc123.s1.eu.hivemq.cloud:8883", 1883);
        c.normalizeBrokerUrl();
        assertEquals("abc123.s1.eu.hivemq.cloud", c.brokerUrl);
        assertEquals(8883, c.port);
        assertEquals("ssl://abc123.s1.eu.hivemq.cloud:8883", c.getBrokerUri());
    }

    @Test
    public void normalizeEmbeddedPortWinsOverPortField() {
        MqttConnectionConfig c = config("broker.example.com:1884", 8883);
        c.normalizeBrokerUrl();
        assertEquals("broker.example.com", c.brokerUrl);
        assertEquals(1884, c.port);
        assertEquals("tcp://broker.example.com:1884", c.getBrokerUri());
    }

    @Test
    public void normalizeHandlesBracketedIpv6AndTrailingSlash() {
        MqttConnectionConfig c = config("[fd7a:115c::1]:8883/", 1883);
        c.normalizeBrokerUrl();
        assertEquals("[fd7a:115c::1]", c.brokerUrl);
        assertEquals(8883, c.port);
    }

    @Test
    public void normalizeLeavesUrlsWithoutEmbeddedPortAlone() {
        String[] untouched = {
                "broker.example.com",
                "ssl://broker.example.com",
                "fd7a:115c::1",          // bare IPv6 literal — not host:port
                "broker.example.com:0",
                "broker.example.com:70000",
                "wss://broker.example.com:99999999999/mqtt",
                "",
        };
        for (String url : untouched) {
            MqttConnectionConfig c = config(url, 1883);
            c.normalizeBrokerUrl();
            assertEquals(url, c.brokerUrl);
            assertEquals(1883, c.port);
            assertEquals("", c.path);
        }
        MqttConnectionConfig nullUrl = config(null, 1883);
        nullUrl.normalizeBrokerUrl();
        assertEquals(1883, nullUrl.port);
    }

    @Test
    public void normalizeSplitsProtocolUrlIntoPortAndPathFields() {
        MqttConnectionConfig c = config("wss://mqtt.eclipseprojects.io:443/mqtt", 1883);
        c.normalizeBrokerUrl();
        assertEquals("wss://mqtt.eclipseprojects.io", c.brokerUrl);
        assertEquals(443, c.port);
        assertEquals("/mqtt", c.path);
        assertEquals("wss://mqtt.eclipseprojects.io:443/mqtt", c.getBrokerUri());

        MqttConnectionConfig ssl = config("ssl://broker.example.com:8883", 1883);
        ssl.normalizeBrokerUrl();
        assertEquals("ssl://broker.example.com", ssl.brokerUrl);
        assertEquals(8883, ssl.port);
    }

    @Test
    public void normalizeUrlPathWinsOverPathFieldAndBlankUrlPathKeepsField() {
        MqttConnectionConfig c = config("wss://broker.example.com/ws", 8884, "/mqtt");
        c.normalizeBrokerUrl();
        assertEquals("wss://broker.example.com", c.brokerUrl);
        assertEquals("/ws", c.path);

        MqttConnectionConfig slashOnly = config("wss://broker.example.com/", 8884, "/mqtt");
        slashOnly.normalizeBrokerUrl();
        assertEquals("wss://broker.example.com", slashOnly.brokerUrl);
        assertEquals("/mqtt", slashOnly.path);
    }

    @Test
    public void normalizeTidiesPathField() {
        MqttConnectionConfig c = config("wss://broker.example.com", 8884, " mqtt/ ");
        c.normalizeBrokerUrl();
        assertEquals("/mqtt", c.path);
    }

    @Test
    public void pathInUrlWithoutPortPutsPortBeforePath() {
        assertEquals("wss://broker.example.com:8884/mqtt",
                config("wss://broker.example.com/mqtt", 8884).getBrokerUri());
    }

    @Test
    public void pathFieldIsAppendedForWebSocketOnly() {
        assertEquals("wss://broker.example.com:443/ws",
                config("wss://broker.example.com", 443, "/ws").getBrokerUri());
        assertEquals("ws://broker.example.com:8000/mqtt",
                config("ws://broker.example.com", 8000, "mqtt").getBrokerUri());
        assertEquals("ssl://broker.example.com:8883",
                config("ssl://broker.example.com", 8883, "/mqtt").getBrokerUri());
        assertEquals("tcp://broker.example.com:1883",
                config("broker.example.com", 1883, "/mqtt").getBrokerUri());
    }

    @Test
    public void inferredWssUsesPathFieldOverDefault() {
        assertEquals("wss://broker.example.com:8884/ws",
                config("broker.example.com", 8884, "/ws").getBrokerUri());
    }

    @Test
    public void explicitWssWithoutPathGetsNoDefaultPath() {
        assertEquals("wss://broker.example.com:8884",
                config("wss://broker.example.com", 8884).getBrokerUri());
    }

    @Test
    public void fromJsonReadsAndNormalizesPath() throws Exception {
        JSONObject json = new JSONObject()
                .put("brokerUrl", "wss://broker.example.com:8884")
                .put("port", 1883)
                .put("path", "mqtt");
        MqttConnectionConfig c = MqttConnectionConfig.fromJson(json);
        assertEquals("wss://broker.example.com", c.brokerUrl);
        assertEquals(8884, c.port);
        assertEquals("/mqtt", c.path);
        assertEquals("/mqtt", c.toJson().getString("path"));
    }

    @Test
    public void fromJsonWithoutPathDefaultsToEmpty() throws Exception {
        MqttConnectionConfig c = MqttConnectionConfig.fromJson(
                new JSONObject().put("brokerUrl", "broker.example.com"));
        assertEquals("", c.path);
    }

    @Test
    public void fromJsonNormalizesEmbeddedPort() throws Exception {
        JSONObject json = new JSONObject()
                .put("brokerUrl", "abc123.s1.eu.hivemq.cloud:8883")
                .put("port", 1883);
        MqttConnectionConfig c = MqttConnectionConfig.fromJson(json);
        assertEquals("abc123.s1.eu.hivemq.cloud", c.brokerUrl);
        assertEquals(8883, c.port);
    }

    @Test
    public void explicitProtocolIsNeverOverridden() {
        assertEquals("tcp://broker.example.com:8883",
                config("tcp://broker.example.com:8883", 8883).getBrokerUri());
        assertEquals("ws://broker.example.com:8884",
                config("ws://broker.example.com", 8884).getBrokerUri());
        assertEquals("wss://mqtt.eclipseprojects.io:443/mqtt",
                config("wss://mqtt.eclipseprojects.io:443/mqtt", 1883).getBrokerUri());
    }

    @Test
    public void trailingSlashIsStripped() {
        assertEquals("ssl://broker.example.com:8883",
                config("broker.example.com/", 8883).getBrokerUri());
    }

    @Test
    public void emptyOrNullUrlYieldsEmptyUri() {
        assertEquals("", config("", 8883).getBrokerUri());
        assertEquals("", config(null, 8883).getBrokerUri());
    }
}
