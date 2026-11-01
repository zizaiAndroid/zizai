// A stand-in for Groq's OpenAI-compatible API, for `wrangler dev` without a real key.
// Point the local server at it with GROQ_BASE_URL=http://127.0.0.1:8799/v1, then: node tools/mock-groq.mjs
import { createServer } from 'node:http';

const port = Number(process.env.PORT ?? 8799);

createServer((req, res) => {
  const chunks = [];
  req.on('data', (c) => chunks.push(c));
  req.on('end', () => {
    const send = (status, body) => {
      res.writeHead(status, { 'content-type': 'application/json' });
      res.end(JSON.stringify(body));
    };
    if (req.url === '/v1/chat/completions') {
      const body = JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}');
      const last = [...(body.messages ?? [])].reverse().find((m) => m.role === 'user');
      const text = typeof last?.content === 'string' ? last.content : '';
      console.log(`chat model=${body.model} max=${body.max_completion_tokens} effort=${body.reasoning_effort}`);
      return send(200, {
        id: 'mock',
        object: 'chat.completion',
        model: body.model,
        choices: [{ index: 0, message: { role: 'assistant', content: `[mock] ${text.split('\n').pop()}` }, finish_reason: 'stop' }],
        usage: { prompt_tokens: Math.ceil(text.length / 4), completion_tokens: 12 },
      });
    }
    if (req.url === '/v1/audio/transcriptions') {
      console.log(`transcription ${Buffer.concat(chunks).length} bytes`);
      return send(200, { text: '[mock transcription]', duration: 5.2 });
    }
    send(404, { error: { message: 'not found' } });
  });
}).listen(port, '127.0.0.1', () => console.log(`mock Groq on http://127.0.0.1:${port}/v1`));
