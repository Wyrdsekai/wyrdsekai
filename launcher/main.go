// Wyrdsekai Go Launcher (§57)
//
// Embeds the server JAR and manages the lifecycle:
//   wyrd start   — Start server in background
//   wyrd stop    — Graceful shutdown
//   wyrd status  — Show running state
//   wyrd setup   — Interactive first-run configuration
//
// Build: go build -o wyrd ./launcher/

package main

import (
	"bufio"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"syscall"
	"time"
)

const (
	defaultHTTPPort   = 8080
	defaultWSPort     = 7070
	defaultTelnetPort = 7777
	pidFileName       = ".server.pid"
	logFileName       = ".server.log"
	minJavaVersion    = 21
)

func main() {
	if len(os.Args) < 2 {
		printUsage()
		os.Exit(0)
	}

	cmd := os.Args[1]
	switch cmd {
	case "start":
		doStart()
	case "stop":
		doStop()
	case "restart":
		doStop()
		time.Sleep(time.Second)
		doStart()
	case "status":
		doStatus()
	case "setup":
		doSetup()
	case "version":
		fmt.Println("wyrd v0.1.0 (M0)")
	default:
		fmt.Fprintf(os.Stderr, "Unknown command: %s\n", cmd)
		printUsage()
		os.Exit(1)
	}
}

func printUsage() {
	fmt.Println(`Usage: wyrd <command>

Commands:
  start     Start the Wyrdsekai server
  stop      Stop the server gracefully
  restart   Stop then start
  status    Show server status
  setup     Interactive first-run configuration
  version   Show version`)
}

func dataDir() string {
	dir := os.Getenv("WYRDSEKAI_HOME")
	if dir == "" {
		home, _ := os.UserHomeDir()
		dir = filepath.Join(home, ".wyrdsekai")
	}
	os.MkdirAll(dir, 0755)
	return dir
}

func pidFile() string {
	return filepath.Join(dataDir(), pidFileName)
}

func logFile() string {
	return filepath.Join(dataDir(), logFileName)
}

func readPid() (int, error) {
	data, err := os.ReadFile(pidFile())
	if err != nil {
		return 0, err
	}
	return strconv.Atoi(strings.TrimSpace(string(data)))
}

func isRunning() bool {
	pid, err := readPid()
	if err != nil {
		return false
	}
	proc, err := os.FindProcess(pid)
	if err != nil {
		return false
	}
	return proc.Signal(syscall.Signal(0)) == nil
}

func findJava() (string, int) {
	javaHome := os.Getenv("JAVA_HOME")
	candidates := []string{}
	if javaHome != "" {
		candidates = append(candidates, filepath.Join(javaHome, "bin", "java"))
	}
	candidates = append(candidates, "java")

	for _, java := range candidates {
		out, err := exec.Command(java, "-version").CombinedOutput()
		if err != nil {
			continue
		}
		// Parse version
		lines := strings.Split(string(out), "\n")
		if len(lines) > 0 {
			ver := extractVersion(lines[0])
			if ver >= minJavaVersion {
				return java, ver
			}
		}
	}
	return "", 0
}

func extractVersion(versionLine string) int {
	// Parse: openjdk version "25" or "21.0.1"
	start := strings.Index(versionLine, `"`)
	if start < 0 {
		return 0
	}
	end := strings.Index(versionLine[start+1:], `"`)
	if end < 0 {
		return 0
	}
	verStr := versionLine[start+1 : start+1+end]
	// Take major version
	parts := strings.Split(verStr, ".")
	major, err := strconv.Atoi(parts[0])
	if err != nil {
		return 0
	}
	return major
}

func detectHardware() {
	fmt.Printf("Platform: %s/%s\n", runtime.GOOS, runtime.GOARCH)

	// Check GPU (NVIDIA)
	if out, err := exec.Command("nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader").Output(); err == nil {
		lines := strings.TrimSpace(string(out))
		if lines != "" {
			fmt.Printf("GPU: %s\n", strings.Split(lines, "\n")[0])
		}
	} else {
		fmt.Println("GPU: None detected (CPU mode)")
	}

	// Java
	java, ver := findJava()
	if java != "" {
		fmt.Printf("Java: version %d (%s)\n", ver, java)
	} else {
		fmt.Printf("Java: NOT FOUND (requires %d+)\n", minJavaVersion)
	}
}

func doStart() {
	if isRunning() {
		pid, _ := readPid()
		fmt.Printf("Server already running (pid %d)\n", pid)
		return
	}

	java, ver := findJava()
	if java == "" {
		fmt.Fprintf(os.Stderr, "Java %d+ not found. Install it and try again.\n", minJavaVersion)
		os.Exit(1)
	}
	fmt.Printf("Using Java %d\n", ver)

	// Find server JAR — check embedded, then local build
	jarPath := findServerJar()
	if jarPath == "" {
		fmt.Fprintln(os.Stderr, "Server JAR not found. Run 'wyrd setup' first.")
		os.Exit(1)
	}

	logF, _ := os.Create(logFile())
	cmd := exec.Command(java,
		"--enable-native-access=ALL-UNNAMED",
		"-jar", jarPath)
	cmd.Stdout = logF
	cmd.Stderr = logF
	cmd.Dir = dataDir()

	if err := cmd.Start(); err != nil {
		fmt.Fprintf(os.Stderr, "Failed to start: %v\n", err)
		os.Exit(1)
	}

	os.WriteFile(pidFile(), []byte(strconv.Itoa(cmd.Process.Pid)), 0644)
	fmt.Printf("Server started (pid %d)\n", cmd.Process.Pid)
	fmt.Printf("Log: %s\n", logFile())
}

func doStop() {
	if !isRunning() {
		fmt.Println("Server not running.")
		return
	}

	pid, _ := readPid()
	proc, err := os.FindProcess(pid)
	if err != nil {
		fmt.Println("Server not running.")
		os.Remove(pidFile())
		return
	}

	fmt.Printf("Stopping server (pid %d)...\n", pid)
	proc.Signal(syscall.SIGTERM)

	// Wait up to 5 seconds
	for i := 0; i < 50; i++ {
		if proc.Signal(syscall.Signal(0)) != nil {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}

	// Force kill if still alive
	if proc.Signal(syscall.Signal(0)) == nil {
		fmt.Println("Force killing...")
		proc.Signal(syscall.SIGKILL)
	}

	os.Remove(pidFile())
	fmt.Println("Server stopped.")
}

func doStatus() {
	if isRunning() {
		pid, _ := readPid()
		fmt.Printf("Server running (pid %d)\n", pid)
	} else {
		fmt.Println("Server not running.")
	}
}

func doSetup() {
	fmt.Println("╔══════════════════════════════════╗")
	fmt.Println("║        Wyrdsekai Setup            ║")
	fmt.Println("╚══════════════════════════════════╝")
	fmt.Println()

	detectHardware()
	fmt.Println()

	reader := bufio.NewReader(os.Stdin)

	// Killer app selection
	fmt.Println("What would you like to use Wyrdsekai for?")
	fmt.Println("  1) Photo companion — organize and enrich your photo library")
	fmt.Println("  2) Family hub — shared calendar, chores, family governance")
	fmt.Println("  3) General purpose — explore the full MUD world")
	fmt.Print("Choice [3]: ")
	choice, _ := reader.ReadString('\n')
	choice = strings.TrimSpace(choice)
	if choice == "" {
		choice = "3"
	}

	switch choice {
	case "1":
		fmt.Println("Photo companion selected — will configure photo rooms.")
	case "2":
		fmt.Println("Family hub selected — will configure family rooms.")
	default:
		fmt.Println("General purpose selected.")
	}

	// Data directory
	fmt.Printf("Data directory [%s]: ", dataDir())
	dir, _ := reader.ReadString('\n')
	dir = strings.TrimSpace(dir)
	if dir == "" {
		dir = dataDir()
	}
	os.MkdirAll(dir, 0755)

	fmt.Println()
	fmt.Println("Setup complete! Run 'wyrd start' to begin.")
}

func findServerJar() string {
	// Check common locations
	locations := []string{
		filepath.Join(dataDir(), "server.jar"),
		"server/build/libs/server-all.jar",
		"server/build/libs/server.jar",
	}
	for _, loc := range locations {
		if _, err := os.Stat(loc); err == nil {
			return loc
		}
	}
	return ""
}
