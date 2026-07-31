// Minimal zone service example.
//
// Start Wyrdsekai, then run:
//
//	go run examples/hello_zone.go
//
// Players can now type: hello.greet, hello.status, hello.echo anything here
package main

import (
	"context"
	"fmt"
	"log"
	"os/signal"
	"strings"
	"syscall"

	zone "github.com/wyrdsekai/wyrdsekai/sdks/go"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	svc := zone.New("hello", "ws://localhost:7070/ws/zone", nil)

	svc.OnAction("greet", func(ctx *zone.CommandContext) {
		ctx.Respond(fmt.Sprintf("Hello, %s! Welcome to the hello zone.", ctx.PlayerID))
	})

	svc.OnAction("status", func(ctx *zone.CommandContext) {
		ctx.Respond("All systems operational. The hello zone is running.")
	})

	svc.OnAction("echo", func(ctx *zone.CommandContext) {
		text := "(nothing to echo)"
		if len(ctx.Args) > 0 {
			text = strings.Join(ctx.Args, " ")
		}
		ctx.Respond(fmt.Sprintf("Echo: %s", text))
	})

	svc.OnDefault(func(ctx *zone.CommandContext) {
		ctx.Respond(fmt.Sprintf(
			"Unknown action '%s'. Try: hello.greet, hello.status, hello.echo <text>",
			ctx.Action,
		))
	})

	log.Println("Starting hello zone service...")
	if err := svc.Run(ctx); err != nil {
		log.Printf("Shut down: %v", err)
	}
}
