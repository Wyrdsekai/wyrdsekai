// OpenClaw Gateway — WebSocket bridge for containerized skill execution
// This is the gateway server that runs inside the Docker container.
// It accepts WebSocket connections from Wyrdsekai's OpenClawGatewayExecutor,
// loads skill definitions from /opt/openclaw/skills/, and executes CLI tools.

const { WebSocketServer } = require('ws');
const { execSync, spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const http = require('http');

const PORT = parseInt(process.env.OPENCLAW_PORT || '18789');
const MAX_EXEC_TIME = parseInt(process.env.OPENCLAW_MAX_EXEC_TIME || '30000');
const MAX_OUTPUT_SIZE = parseInt(process.env.OPENCLAW_MAX_OUTPUT_SIZE || '65536');
const SKILLS_DIR = process.env.OPENCLAW_SKILLS_DIR || '/opt/openclaw/skills';

// --- Skill Catalogue ---

const skills = new Map();

function loadSkills() {
    skills.clear();
    if (!fs.existsSync(SKILLS_DIR)) return;

    const dirs = fs.readdirSync(SKILLS_DIR, { withFileTypes: true })
        .filter(d => d.isDirectory());

    for (const dir of dirs) {
        const skillMd = path.join(SKILLS_DIR, dir.name, 'SKILL.md');
        if (fs.existsSync(skillMd)) {
            const content = fs.readFileSync(skillMd, 'utf8');
            const name = dir.name;
            const tools = parseSkillMd(content, name);
            for (const tool of tools) {
                skills.set(tool.id, tool);
            }
        }
    }
    console.log(`Loaded ${skills.size} skills from ${SKILLS_DIR}`);
}

function parseSkillMd(content, cliName) {
    const tools = [];
    const lines = content.split('\n');
    let currentTool = null;
    let currentDesc = null;
    let currentParams = [];

    for (const line of lines) {
        const toolMatch = line.match(/^##\s+(.+)$/);
        if (toolMatch) {
            if (currentTool) {
                tools.push({
                    id: `openclaw.${cliName}.${currentTool.toLowerCase().replace(/\s+/g, '-')}`,
                    name: currentTool,
                    description: currentDesc || currentTool,
                    cli: cliName,
                    params: currentParams
                });
            }
            currentTool = toolMatch[1].trim();
            currentDesc = null;
            currentParams = [];
            continue;
        }

        const descMatch = line.match(/^\*\*Description\*\*\s*:?\s*(.+)$/);
        if (descMatch) {
            currentDesc = descMatch[1].trim();
            continue;
        }

        const paramMatch = line.match(/^\|\s*`?(\w+)`?\s*\|\s*`?(\w+)`?\s*\|\s*(true|false|yes|no)\s*\|\s*(.+?)\s*\|$/);
        if (paramMatch) {
            currentParams.push({
                name: paramMatch[1],
                type: paramMatch[2],
                required: paramMatch[3] === 'true' || paramMatch[3] === 'yes',
                description: paramMatch[4].trim()
            });
        }
    }

    if (currentTool) {
        tools.push({
            id: `openclaw.${cliName}.${currentTool.toLowerCase().replace(/\s+/g, '-')}`,
            name: currentTool,
            description: currentDesc || currentTool,
            cli: cliName,
            params: currentParams
        });
    }

    return tools;
}

// --- Skill Execution ---

function executeSkill(skill, params, env, timeout) {
    return new Promise((resolve, reject) => {
        const args = [];
        for (const p of skill.params) {
            if (params[p.name] !== undefined) {
                args.push(`--${p.name}`, String(params[p.name]));
            }
        }

        const proc = spawn(skill.cli, args, {
            env: { ...process.env, ...env },
            timeout: timeout || MAX_EXEC_TIME,
            maxBuffer: MAX_OUTPUT_SIZE
        });

        let stdout = '';
        let stderr = '';

        proc.stdout.on('data', (data) => {
            stdout += data.toString();
            if (stdout.length > MAX_OUTPUT_SIZE) {
                proc.kill();
                stdout = stdout.substring(0, MAX_OUTPUT_SIZE) + '\n[truncated]';
            }
        });

        proc.stderr.on('data', (data) => {
            stderr += data.toString();
        });

        proc.on('close', (code) => {
            resolve({
                success: code === 0,
                output: stdout.trim(),
                stderr: stderr.trim(),
                exitCode: code
            });
        });

        proc.on('error', (err) => {
            reject(err);
        });
    });
}

// --- HTTP Health Endpoint ---

const httpServer = http.createServer((req, res) => {
    if (req.url === '/health') {
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({
            status: 'healthy',
            skills: skills.size,
            uptime: process.uptime()
        }));
    } else {
        res.writeHead(404);
        res.end();
    }
});

// --- WebSocket Server ---

const wss = new WebSocketServer({ server: httpServer });

wss.on('connection', (ws) => {
    console.log('Client connected');

    ws.on('message', async (raw) => {
        try {
            const msg = JSON.parse(raw.toString());

            if (msg.type === 'catalogue') {
                const catalogue = Array.from(skills.values()).map(s => ({
                    id: s.id,
                    name: s.name,
                    description: s.description,
                    params: s.params
                }));
                ws.send(JSON.stringify({ type: 'catalogue', skills: catalogue }));
                return;
            }

            if (msg.type === 'invoke') {
                const skill = skills.get(msg.skillId);
                if (!skill) {
                    ws.send(JSON.stringify({
                        type: 'error',
                        requestId: msg.requestId,
                        skillId: msg.skillId,
                        message: 'Skill not found: ' + msg.skillId
                    }));
                    return;
                }

                const startTime = Date.now();
                try {
                    const result = await executeSkill(
                        skill,
                        msg.params || {},
                        msg.env || {},
                        msg.timeout || MAX_EXEC_TIME
                    );
                    ws.send(JSON.stringify({
                        type: 'result',
                        requestId: msg.requestId,
                        skillId: msg.skillId,
                        success: result.success,
                        output: result.output,
                        meta: { exitCode: result.exitCode },
                        latencyMs: Date.now() - startTime
                    }));
                } catch (err) {
                    ws.send(JSON.stringify({
                        type: 'error',
                        requestId: msg.requestId,
                        skillId: msg.skillId,
                        message: err.message
                    }));
                }
                return;
            }

        } catch (err) {
            console.error('Message parse error:', err.message);
        }
    });

    ws.on('close', () => {
        console.log('Client disconnected');
    });
});

// --- Startup ---

loadSkills();
httpServer.listen(PORT, () => {
    console.log(`OpenClaw Gateway listening on port ${PORT}`);
    console.log(`${skills.size} skills loaded`);
});
