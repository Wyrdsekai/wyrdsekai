//! Wyrdsekai Zone Bridge SDK for Rust.
//!
//! Connect external services to Wyrdsekai as first-class zone handlers.
//!
//! ```no_run
//! use wyrdsekai_zone::{ZoneService, CommandContext};
//!
//! #[tokio::main]
//! async fn main() {
//!     let mut svc = ZoneService::new("myservice", "ws://localhost:7070/ws/zone");
//!     svc.on_action("status", |ctx| Box::pin(async move {
//!         ctx.respond("All systems go.").await
//!     }));
//!     svc.run().await.unwrap();
//! }
//! ```

use std::collections::HashMap;
use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;
use std::time::Duration;

use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use tokio::sync::Mutex;
use tokio_tungstenite::{connect_async, tungstenite::Message};

// ── Message types ──

#[derive(Debug, Serialize)]
pub struct Register {
    #[serde(rename = "type")]
    pub msg_type: String,
    pub namespace: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub secret: Option<String>,
}

#[derive(Debug, Deserialize)]
pub struct ForwardCommand {
    #[serde(rename = "requestId")]
    pub request_id: String,
    #[serde(rename = "playerId")]
    pub player_id: String,
    pub action: String,
    #[serde(default)]
    pub args: Vec<String>,
    #[serde(default)]
    pub payload: serde_json::Map<String, serde_json::Value>,
}

#[derive(Debug, Serialize)]
struct CommandResponse {
    #[serde(rename = "type")]
    msg_type: String,
    #[serde(rename = "requestId")]
    request_id: String,
    #[serde(rename = "playerId")]
    player_id: String,
    messages: Vec<serde_json::Value>,
}

#[derive(Debug, Serialize)]
struct BroadcastMsg {
    #[serde(rename = "type")]
    msg_type: String,
    #[serde(rename = "roomId")]
    #[serde(skip_serializing_if = "Option::is_none")]
    room_id: Option<String>,
    messages: Vec<serde_json::Value>,
}

#[derive(Debug, Clone, Serialize)]
pub struct ContentBlock {
    pub format: String,
    pub data: serde_json::Map<String, serde_json::Value>,
    pub fallback: String,
}

/// Build a standard prose S2C message.
pub fn prose(speaker: &str, text: &str) -> serde_json::Value {
    serde_json::json!({
        "type": "prose",
        "seq": 0,
        "speaker": speaker,
        "text": text,
        "hints": [],
        "contentBlocks": null,
        "priority": "normal",
        "locale": "en"
    })
}

// ── Command context ──

/// Context passed to action handlers.
pub struct CommandContext {
    pub command: ForwardCommand,
    pub action: String,
    pub args: Vec<String>,
    pub player_id: String,
    pub request_id: String,
    namespace: String,
    sender: Arc<Mutex<futures_util::stream::SplitSink<
        tokio_tungstenite::WebSocketStream<
            tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>
        >,
        Message,
    >>>,
}

impl CommandContext {
    /// Send a prose response to the player.
    pub async fn respond(&self, text: &str) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        self.respond_as(&self.namespace, text).await
    }

    /// Send a prose response with a custom speaker name.
    pub async fn respond_as(&self, speaker: &str, text: &str) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let resp = CommandResponse {
            msg_type: "response".into(),
            request_id: self.request_id.clone(),
            player_id: self.player_id.clone(),
            messages: vec![prose(speaker, text)],
        };
        let json = serde_json::to_string(&resp)?;
        self.sender.lock().await.send(Message::Text(json.into())).await?;
        Ok(())
    }

    /// Send an error response.
    pub async fn error(&self, message: &str, code: &str) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        let resp = CommandResponse {
            msg_type: "response".into(),
            request_id: self.request_id.clone(),
            player_id: self.player_id.clone(),
            messages: vec![serde_json::json!({
                "type": "error",
                "seq": 0,
                "code": code,
                "message": message,
                "requestId": self.request_id,
            })],
        };
        let json = serde_json::to_string(&resp)?;
        self.sender.lock().await.send(Message::Text(json.into())).await?;
        Ok(())
    }
}

// ── Service ──

type BoxFut = Pin<Box<dyn Future<Output = Result<(), Box<dyn std::error::Error + Send + Sync>>> + Send>>;
type Handler = Arc<dyn Fn(CommandContext) -> BoxFut + Send + Sync>;

pub struct ZoneService {
    pub namespace: String,
    pub url: String,
    pub secret: Option<String>,
    handlers: HashMap<String, Handler>,
    default_handler: Option<Handler>,
}

impl ZoneService {
    pub fn new(namespace: &str, url: &str) -> Self {
        Self {
            namespace: namespace.into(),
            url: url.into(),
            secret: None,
            handlers: HashMap::new(),
            default_handler: None,
        }
    }

    pub fn with_secret(mut self, secret: &str) -> Self {
        self.secret = Some(secret.into());
        self
    }

    pub fn on_action<F, Fut>(&mut self, action: &str, handler: F)
    where
        F: Fn(CommandContext) -> Fut + Send + Sync + 'static,
        Fut: Future<Output = Result<(), Box<dyn std::error::Error + Send + Sync>>> + Send + 'static,
    {
        let h = Arc::new(move |ctx| -> BoxFut { Box::pin(handler(ctx)) });
        self.handlers.insert(action.into(), h);
    }

    pub fn on_default<F, Fut>(&mut self, handler: F)
    where
        F: Fn(CommandContext) -> Fut + Send + Sync + 'static,
        Fut: Future<Output = Result<(), Box<dyn std::error::Error + Send + Sync>>> + Send + 'static,
    {
        self.default_handler = Some(Arc::new(move |ctx| -> BoxFut { Box::pin(handler(ctx)) }));
    }

    /// Run the service with auto-reconnect. Blocks until cancelled.
    pub async fn run(&self) -> Result<(), Box<dyn std::error::Error>> {
        let mut delay = Duration::from_secs(1);
        loop {
            match self.connect_and_serve().await {
                Ok(()) => break,
                Err(e) => {
                    log::warn!("[{}] Connection lost: {}", self.namespace, e);
                    let d = delay.min(Duration::from_secs(30));
                    log::info!("[{}] Reconnecting in {:?}...", self.namespace, d);
                    tokio::time::sleep(d).await;
                    delay *= 2;
                }
            }
        }
        Ok(())
    }

    async fn connect_and_serve(&self) -> Result<(), Box<dyn std::error::Error>> {
        let (ws, _) = connect_async(&self.url).await?;
        let (write, mut read) = ws.split();
        let sender = Arc::new(Mutex::new(write));

        // Register
        let reg = Register {
            msg_type: "register".into(),
            namespace: self.namespace.clone(),
            secret: self.secret.clone(),
        };
        sender.lock().await.send(Message::Text(serde_json::to_string(&reg)?.into())).await?;

        // Wait for registration
        if let Some(Ok(Message::Text(text))) = read.next().await {
            let text_str: &str = &text;
            let data: serde_json::Value = serde_json::from_str(text_str)?;
            if data["type"] == "error" {
                return Err(format!("Registration failed: {}", data["reason"]).into());
            }
            log::info!("[{}] Registered at {}", self.namespace, self.url);
        }

        // Serve
        while let Some(msg) = read.next().await {
            let msg = msg?;
            if let Message::Text(text) = msg {
                let text_str: &str = &text;
                let data: serde_json::Value = serde_json::from_str(text_str)?;
                if data["type"] == "command" {
                    let cmd: ForwardCommand = serde_json::from_value(data)?;
                    let ctx = CommandContext {
                        action: cmd.action.clone(),
                        args: cmd.args.clone(),
                        player_id: cmd.player_id.clone(),
                        request_id: cmd.request_id.clone(),
                        namespace: self.namespace.clone(),
                        sender: sender.clone(),
                        command: cmd,
                    };
                    let handler = self.handlers.get(&ctx.action)
                        .cloned()
                        .or_else(|| self.default_handler.clone());
                    if let Some(h) = handler {
                        tokio::spawn(async move {
                            if let Err(e) = h(ctx).await {
                                log::error!("Handler error: {}", e);
                            }
                        });
                    }
                }
            }
        }
        Ok(())
    }
}
