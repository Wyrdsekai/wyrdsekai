//! Minimal zone service example.
//!
//! Start Wyrdsekai, then run:
//!     cargo run --example hello_zone
//!
//! Players can now type: hello.greet, hello.status, hello.echo anything here

use wyrdsekai_zone::{ZoneService, CommandContext};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    env_logger::init();

    let mut svc = ZoneService::new("hello", "ws://localhost:7070/ws/zone");

    svc.on_action("greet", |ctx: CommandContext| async move {
        ctx.respond(&format!("Hello, {}! Welcome to the hello zone.", ctx.player_id)).await
    });

    svc.on_action("status", |ctx: CommandContext| async move {
        ctx.respond("All systems operational. The hello zone is running.").await
    });

    svc.on_action("echo", |ctx: CommandContext| async move {
        let text = if ctx.args.is_empty() {
            "(nothing to echo)".to_string()
        } else {
            ctx.args.join(" ")
        };
        ctx.respond(&format!("Echo: {}", text)).await
    });

    svc.on_default(|ctx: CommandContext| async move {
        ctx.respond(&format!(
            "Unknown action '{}'. Try: hello.greet, hello.status, hello.echo <text>",
            ctx.action
        )).await
    });

    println!("Starting hello zone service...");
    svc.run().await
}
