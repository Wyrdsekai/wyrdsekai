// Wyrdsekai Zone Bridge SDK for Pike.
//
// Connect external services to Wyrdsekai as first-class zone handlers.
// Pike — descended from LPC, the language that built the MUDs.
//
// Uses raw TCP + manual WebSocket handshake for maximum compatibility.
// Requires Pike 8.0+.

#charset utf-8

protected string namespace;
protected string ws_host;
protected int ws_port;
protected string ws_path;
protected string|void zone_secret;
protected mapping(string:function) action_handlers = ([]);
protected function|void default_handler;
protected Stdio.File sock;
protected int connected;

//! Initialize the zone service.
void init(string ns, string url, string|void secret)
{
    namespace = ns;
    zone_secret = secret;

    // Parse ws://host:port/path
    sscanf(url, "ws://%s:%d%s", ws_host, ws_port, ws_path);
    if (!ws_host) {
        sscanf(url, "ws://%s%s", ws_host, ws_path);
        ws_port = 80;
    }
    if (!ws_path || ws_path == "") ws_path = "/";
}

//! Register a handler for a specific action.
void on_action(string action, function handler)
{
    action_handlers[action] = handler;
}

//! Register a fallback handler for unmatched actions.
void on_default(function handler)
{
    default_handler = handler;
}

//! Build a prose S2C message.
mapping prose(string speaker, string text)
{
    return ([
        "type": "prose",
        "seq": 0,
        "speaker": speaker,
        "text": text,
        "hints": ({}),
        "priority": "normal",
        "locale": "en",
    ]);
}

//! Send a response to a forwarded command.
void send_response(string request_id, string player_id, string text)
{
    mapping resp = ([
        "type": "response",
        "requestId": request_id,
        "playerId": player_id,
        "messages": ({ prose(namespace, text) }),
    ]);
    ws_send_text(Standards.JSON.encode(resp));
}

//! Send an error response.
void send_error_response(string request_id, string player_id,
                          string message, string code)
{
    mapping resp = ([
        "type": "response",
        "requestId": request_id,
        "playerId": player_id,
        "messages": ({
            ([
                "type": "error",
                "seq": 0,
                "code": code,
                "message": message,
                "requestId": request_id,
            ])
        }),
    ]);
    ws_send_text(Standards.JSON.encode(resp));
}

//! Broadcast a message to all zone players.
void broadcast(string text, string|void room_id)
{
    mapping msg = ([
        "type": "broadcast",
        "messages": ({ prose(namespace, text) }),
    ]);
    if (room_id)
        msg["roomId"] = room_id;
    ws_send_text(Standards.JSON.encode(msg));
}

//! Connect, register, and serve. Returns -1 for Pike backend loop.
int run()
{
    do_connect();
    // Keep the backend alive — poll for WebSocket data every second
    call_out(poll_loop, 1);
    return -1;
}

protected void poll_loop()
{
    // Re-schedule to keep backend alive
    call_out(poll_loop, 1);
}

// ── WebSocket implementation (raw socket) ──

protected string ws_buffer = "";
protected int ws_handshake_done = 0;

protected void do_connect()
{
    write("[%s] Connecting to %s:%d%s...\n", namespace, ws_host, ws_port, ws_path);

    sock = Stdio.File();
    if (!sock->connect(ws_host, ws_port)) {
        write("[%s] TCP connect failed\n", namespace);
        call_out(do_connect, 5);
        return;
    }

    // Send WebSocket upgrade (synchronous)
    string key = MIME.encode_base64(random_string(16));
    string req = sprintf(
        "GET %s HTTP/1.1\r\n"
        "Host: %s:%d\r\n"
        "Upgrade: websocket\r\n"
        "Connection: Upgrade\r\n"
        "Sec-WebSocket-Key: %s\r\n"
        "Sec-WebSocket-Version: 13\r\n"
        "\r\n",
        ws_path, ws_host, ws_port, key);
    sock->write(req);

    // Read HTTP response synchronously
    string resp = "";
    while (search(resp, "\r\n\r\n") < 0) {
        string chunk = sock->read(4096, 1);
        if (!chunk || sizeof(chunk) == 0) {
            write("[%s] Handshake read failed\n", namespace);
            call_out(do_connect, 5);
            return;
        }
        resp += chunk;
    }

    if (search(resp, "101") < 0) {
        write("[%s] WebSocket upgrade failed: %s\n", namespace, (resp / "\r\n")[0]);
        sock->close();
        call_out(do_connect, 5);
        return;
    }

    // Extract any data after headers
    int hdr_end = search(resp, "\r\n\r\n");
    ws_buffer = resp[hdr_end+4..];
    ws_handshake_done = 1;
    write("[%s] WebSocket connected\n", namespace);

    // Register
    mapping reg = ([ "type": "register", "namespace": namespace ]);
    if (zone_secret) reg["secret"] = zone_secret;
    ws_send_text(Standards.JSON.encode(reg));

    // Read registration response synchronously
    string reg_resp = ws_read_frame_sync();
    if (reg_resp) {
        handle_ws_message(reg_resp);
    }

    // Switch to non-blocking for ongoing command handling
    sock->set_nonblocking(ws_read_callback, 0, ws_close_callback);
}

protected void heartbeat()
{
    if (connected && sock) {
        // Send WebSocket ping frame to keep connection alive
        ws_send_frame(0x9, "");
    }
    call_out(heartbeat, 30);
}

protected string|void ws_read_frame_sync()
{
    // Read a single frame synchronously
    string hdr = sock->read(2);
    if (!hdr || sizeof(hdr) < 2) return UNDEFINED;
    int opcode = hdr[0] & 0x0F;
    int is_masked = hdr[1] & 0x80;
    int plen = hdr[1] & 0x7F;
    if (plen == 126) {
        string ext = sock->read(2);
        plen = (ext[0] << 8) | ext[1];
    } else if (plen == 127) {
        string ext = sock->read(8);
        plen = 0;
        for (int i = 0; i < 8; i++) plen = (plen << 8) | ext[i];
    }
    string mask_key = "";
    if (is_masked) mask_key = sock->read(4);
    string payload = plen > 0 ? sock->read(plen) : "";
    if (is_masked && sizeof(mask_key) == 4) {
        string unmasked = "";
        for (int i = 0; i < sizeof(payload); i++)
            unmasked += sprintf("%c", payload[i] ^ mask_key[i % 4]);
        payload = unmasked;
    }
    if (opcode == 0x1) return payload; // Text
    return UNDEFINED;
}

protected void ws_read_callback(mixed id, string data)
{
    ws_buffer += data;

    if (!ws_handshake_done) {
        // Look for end of HTTP headers
        int hdr_end = search(ws_buffer, "\r\n\r\n");
        if (hdr_end < 0) return; // Need more data

        string headers = ws_buffer[..hdr_end-1];
        ws_buffer = ws_buffer[hdr_end+4..];

        if (search(headers, "101") < 0) {
            write("[%s] WebSocket upgrade failed: %s\n", namespace,
                  (headers / "\r\n")[0]);
            sock->close();
            call_out(do_connect, 5);
            return;
        }

        ws_handshake_done = 1;
        write("[%s] WebSocket connected\n", namespace);

        // Register
        mapping reg = ([ "type": "register", "namespace": namespace ]);
        if (zone_secret) reg["secret"] = zone_secret;
        ws_send_text(Standards.JSON.encode(reg));
    }

    // Process WebSocket frames
    while (sizeof(ws_buffer) >= 2) {
        int b0 = ws_buffer[0];
        int b1 = ws_buffer[1];
        int opcode = b0 & 0x0F;
        int masked = b1 & 0x80;
        int payload_len = b1 & 0x7F;
        int offset = 2;

        if (payload_len == 126) {
            if (sizeof(ws_buffer) < 4) return;
            payload_len = (ws_buffer[2] << 8) | ws_buffer[3];
            offset = 4;
        } else if (payload_len == 127) {
            if (sizeof(ws_buffer) < 10) return;
            payload_len = 0;
            for (int i = 0; i < 8; i++)
                payload_len = (payload_len << 8) | ws_buffer[2+i];
            offset = 10;
        }

        if (masked) offset += 4;

        if (sizeof(ws_buffer) < offset + payload_len) return; // Need more data

        string payload = ws_buffer[offset..offset+payload_len-1];

        if (masked) {
            string mask_key = ws_buffer[offset-4..offset-1];
            string unmasked = "";
            for (int i = 0; i < sizeof(payload); i++)
                unmasked += sprintf("%c", payload[i] ^ mask_key[i % 4]);
            payload = unmasked;
        }

        ws_buffer = ws_buffer[offset+payload_len..];

        if (opcode == 0x1) { // Text frame
            handle_ws_message(payload);
        } else if (opcode == 0x9) { // Ping → Pong
            ws_send_frame(0xA, "");
        } else if (opcode == 0x8) { // Close
            sock->close();
            return;
        }
    }
}

protected void ws_close_callback(mixed id)
{
    connected = 0;
    write("[%s] Disconnected. Reconnecting in 5s...\n", namespace);
    call_out(do_connect, 5);
}

protected void ws_send_text(string text)
{
    ws_send_frame(0x1, text);
}

protected void ws_send_frame(int opcode, string payload)
{
    if (!sock) return;
    string mask_key = random_string(4);
    string header = "";

    header += sprintf("%c", 0x80 | opcode); // FIN + opcode
    int len = sizeof(payload);
    if (len < 126)
        header += sprintf("%c", 0x80 | len); // Masked
    else if (len < 65536) {
        header += sprintf("%c", 0x80 | 126);
        header += sprintf("%c%c", (len >> 8) & 0xFF, len & 0xFF);
    } else {
        header += sprintf("%c", 0x80 | 127);
        for (int i = 7; i >= 0; i--)
            header += sprintf("%c", (len >> (i * 8)) & 0xFF);
    }
    header += mask_key;

    // Mask payload
    string masked = "";
    for (int i = 0; i < sizeof(payload); i++)
        masked += sprintf("%c", payload[i] ^ mask_key[i % 4]);

    sock->write(header + masked);
}

protected void handle_ws_message(string text)
{
    mixed data;
    mixed err = catch { data = Standards.JSON.decode(text); };
    if (err) {
        write("[%s] JSON parse error\n", namespace);
        return;
    }

    switch (data->type) {
    case "registered":
        connected = 1;
        write("[%s] Registered as '%s'\n", namespace, data->namespace);
        break;

    case "error":
        write("[%s] Error: %s\n", namespace, data->reason || "unknown");
        break;

    case "command":
        dispatch_command(data);
        break;
    }
}

protected void dispatch_command(mapping cmd)
{
    string action = cmd->action;
    string request_id = cmd->requestId;
    string player_id = cmd->playerId;

    function respond = lambda(string text) {
        send_response(request_id, player_id, text);
    };

    function handler = action_handlers[action] || default_handler;

    if (handler) {
        mixed err = catch {
            handler(cmd, respond);
        };
        if (err) {
            write("[%s] Handler error for '%s': %O\n",
                  namespace, action, err);
            send_error_response(request_id, player_id,
                                sprintf("Internal error: %O", err), "zone_error");
        }
    } else {
        send_error_response(request_id, player_id,
                            sprintf("Unknown action: %s", action), "unknown_action");
    }
}
