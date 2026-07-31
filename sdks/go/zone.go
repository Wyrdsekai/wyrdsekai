// Package zone provides a Go client for the Wyrdsekai Zone Bridge protocol.
//
// Connect external services to Wyrdsekai as first-class zone handlers.
//
//	svc := zone.New("myservice", "ws://localhost:7070/ws/zone", nil)
//	svc.OnAction("status", func(ctx *zone.CommandContext) {
//		ctx.Respond("All systems go.")
//	})
//	svc.Run(context.Background())
package zone

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"sync"
	"time"

	"nhooyr.io/websocket"
	"nhooyr.io/websocket/wsjson"
)

// Message types for the zone bridge protocol.

type Register struct {
	Type      string `json:"type"`
	Namespace string `json:"namespace"`
	Secret    string `json:"secret,omitempty"`
}

type ForwardCommand struct {
	Type      string            `json:"type"`
	RequestID string            `json:"requestId"`
	PlayerID  string            `json:"playerId"`
	Action    string            `json:"action"`
	Args      []string          `json:"args"`
	Payload   map[string]any    `json:"payload"`
}

type CommandResponse struct {
	Type      string `json:"type"`
	RequestID string `json:"requestId"`
	PlayerID  string `json:"playerId"`
	Messages  []any  `json:"messages"`
}

type Broadcast struct {
	Type     string `json:"type"`
	RoomID   string `json:"roomId,omitempty"`
	Messages []any  `json:"messages"`
}

type ContentBlock struct {
	Format   string         `json:"format"`
	Data     map[string]any `json:"data"`
	Fallback string         `json:"fallback"`
}

// Prose builds a standard prose S2C message.
func Prose(speaker, text string) map[string]any {
	return map[string]any{
		"type":          "prose",
		"seq":           0,
		"speaker":       speaker,
		"text":          text,
		"hints":         []any{},
		"contentBlocks": nil,
		"priority":      "normal",
		"locale":        "en",
	}
}

// CommandContext is passed to action handlers.
type CommandContext struct {
	Command   ForwardCommand
	Action    string
	Args      []string
	Payload   map[string]any
	PlayerID  string
	RequestID string
	svc       *ZoneService
}

// Respond sends a prose message to the player who issued the command.
func (ctx *CommandContext) Respond(text string) error {
	return ctx.RespondAs(ctx.svc.Namespace, text)
}

// RespondAs sends a prose message with a custom speaker name.
func (ctx *CommandContext) RespondAs(speaker, text string) error {
	resp := CommandResponse{
		Type:      "response",
		RequestID: ctx.RequestID,
		PlayerID:  ctx.PlayerID,
		Messages:  []any{Prose(speaker, text)},
	}
	return ctx.svc.send(resp)
}

// RespondRaw sends raw S2C messages.
func (ctx *CommandContext) RespondRaw(messages []any) error {
	resp := CommandResponse{
		Type:      "response",
		RequestID: ctx.RequestID,
		PlayerID:  ctx.PlayerID,
		Messages:  messages,
	}
	return ctx.svc.send(resp)
}

// Error sends an error response.
func (ctx *CommandContext) Error(message, code string) error {
	resp := CommandResponse{
		Type:      "response",
		RequestID: ctx.RequestID,
		PlayerID:  ctx.PlayerID,
		Messages: []any{map[string]any{
			"type":      "error",
			"seq":       0,
			"code":      code,
			"message":   message,
			"requestId": ctx.RequestID,
		}},
	}
	return ctx.svc.send(resp)
}

// ActionHandler processes a forwarded command.
type ActionHandler func(ctx *CommandContext)

// ZoneService is a Wyrdsekai zone bridge client.
type ZoneService struct {
	Namespace string
	URL       string
	Secret    string

	handlers       map[string]ActionHandler
	defaultHandler ActionHandler
	conn           *websocket.Conn
	mu             sync.Mutex
}

// New creates a zone service. Pass nil secret for no authentication.
func New(namespace, url string, secret *string) *ZoneService {
	s := &ZoneService{
		Namespace: namespace,
		URL:       url,
		handlers:  make(map[string]ActionHandler),
	}
	if secret != nil {
		s.Secret = *secret
	}
	return s
}

// OnAction registers a handler for a specific action.
func (s *ZoneService) OnAction(action string, handler ActionHandler) {
	s.handlers[action] = handler
}

// OnDefault registers a fallback handler for unmatched actions.
func (s *ZoneService) OnDefault(handler ActionHandler) {
	s.defaultHandler = handler
}

// Broadcast sends an unsolicited message to all zone players.
func (s *ZoneService) Broadcast(ctx context.Context, text string) error {
	return s.send(Broadcast{
		Type:     "broadcast",
		Messages: []any{Prose(s.Namespace, text)},
	})
}

// Run connects and serves until ctx is cancelled. Reconnects on failure.
func (s *ZoneService) Run(ctx context.Context) error {
	delay := time.Second
	for {
		err := s.connectAndServe(ctx)
		if ctx.Err() != nil {
			return ctx.Err()
		}
		log.Printf("[%s] Connection lost: %v", s.Namespace, err)
		if delay > 30*time.Second {
			delay = 30 * time.Second
		}
		log.Printf("[%s] Reconnecting in %v...", s.Namespace, delay)
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(delay):
		}
		delay *= 2
	}
}

func (s *ZoneService) connectAndServe(ctx context.Context) error {
	conn, _, err := websocket.Dial(ctx, s.URL, nil)
	if err != nil {
		return fmt.Errorf("dial: %w", err)
	}
	defer conn.CloseNow()

	s.mu.Lock()
	s.conn = conn
	s.mu.Unlock()

	// Register
	reg := Register{Type: "register", Namespace: s.Namespace, Secret: s.Secret}
	if err := wsjson.Write(ctx, conn, reg); err != nil {
		return fmt.Errorf("register: %w", err)
	}

	// Wait for registration response
	var resp map[string]any
	if err := wsjson.Read(ctx, conn, &resp); err != nil {
		return fmt.Errorf("read registration: %w", err)
	}
	if resp["type"] == "error" {
		return fmt.Errorf("registration failed: %v", resp["reason"])
	}

	log.Printf("[%s] Registered at %s", s.Namespace, s.URL)

	// Serve
	for {
		var msg json.RawMessage
		if err := wsjson.Read(ctx, conn, &msg); err != nil {
			return err
		}
		var envelope struct {
			Type string `json:"type"`
		}
		json.Unmarshal(msg, &envelope)

		if envelope.Type == "command" {
			var cmd ForwardCommand
			json.Unmarshal(msg, &cmd)
			go s.dispatch(&cmd)
		}
	}
}

func (s *ZoneService) dispatch(cmd *ForwardCommand) {
	ctx := &CommandContext{
		Command:   *cmd,
		Action:    cmd.Action,
		Args:      cmd.Args,
		Payload:   cmd.Payload,
		PlayerID:  cmd.PlayerID,
		RequestID: cmd.RequestID,
		svc:       s,
	}
	handler, ok := s.handlers[cmd.Action]
	if !ok {
		handler = s.defaultHandler
	}
	if handler != nil {
		func() {
			defer func() {
				if r := recover(); r != nil {
					log.Printf("[%s] Handler panic for '%s': %v", s.Namespace, cmd.Action, r)
					ctx.Error(fmt.Sprintf("Internal error: %v", r), "zone_error")
				}
			}()
			handler(ctx)
		}()
	} else {
		ctx.Error(fmt.Sprintf("Unknown action: %s", cmd.Action), "unknown_action")
	}
}

func (s *ZoneService) send(v any) error {
	s.mu.Lock()
	conn := s.conn
	s.mu.Unlock()
	if conn == nil {
		return fmt.Errorf("not connected")
	}
	return wsjson.Write(context.Background(), conn, v)
}
