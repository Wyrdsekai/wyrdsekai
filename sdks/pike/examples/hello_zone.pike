// Minimal zone service example for Pike.
//
// Start Wyrdsekai, then run:
//   pike examples/hello_zone.pike
//
// Players can now type: hello.greet, hello.status, hello.echo anything here
//
// Pike — descended from LPC, the language that built the MUDs.
// This is the language coming home.

inherit "../zone.pike";

int main(int argc, array(string) argv)
{
    init("hello", "ws://localhost:7070/ws/zone");

    on_action("greet", lambda(mapping cmd, function respond) {
        respond(sprintf("Hello, %s! Welcome to the hello zone.", cmd->playerId));
    });

    on_action("status", lambda(mapping cmd, function respond) {
        respond("All systems operational. The hello zone is running.");
    });

    on_action("echo", lambda(mapping cmd, function respond) {
        string text = sizeof(cmd->args) > 0
            ? (cmd->args * " ")
            : "(nothing to echo)";
        respond(sprintf("Echo: %s", text));
    });

    on_default(lambda(mapping cmd, function respond) {
        respond(sprintf(
            "Unknown action '%s'. Try: hello.greet, hello.status, hello.echo <text>",
            cmd->action));
    });

    write("Starting hello zone service...\n");
    return run();
}
