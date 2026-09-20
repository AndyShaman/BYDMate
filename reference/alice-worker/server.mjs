import "dotenv/config";
import http from "node:http";
import { D1SqliteCompat } from "./d1-sqlite.mjs";
import worker from "./worker.mjs";

const port = Number(process.env.PORT || 8787);
const dbPath = process.env.DB_PATH || "./bydmate-alice.sqlite";
const apiKey = String(process.env.BYDMATE_API_KEY || "").trim();
const yandexClientId = String(process.env.YANDEX_CLIENT_ID || "").trim();

if (!apiKey) throw new Error("BYDMATE_API_KEY is required");
if (!yandexClientId) throw new Error("YANDEX_CLIENT_ID is required");

const env = {
  DB: new D1SqliteCompat(dbPath),
  BYDMATE_API_KEY: apiKey,
  YANDEX_CLIENT_ID: yandexClientId,
};

async function requestBody(req) {
  if (req.method === "GET" || req.method === "HEAD") return undefined;
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  return chunks.length ? Buffer.concat(chunks) : undefined;
}

const server = http.createServer(async (req, res) => {
  try {
    const host = req.headers.host || `127.0.0.1:${port}`;
    const url = `http://${host}${req.url || "/"}`;
    const body = await requestBody(req);
    const request = new Request(url, {
      method: req.method,
      headers: req.headers,
      body,
    });

    const response = await worker.fetch(request, env);
    res.statusCode = response.status;
    for (const [name, value] of response.headers) res.setHeader(name, value);
    if (req.method === "HEAD") return res.end();
    res.end(Buffer.from(await response.arrayBuffer()));
  } catch (error) {
    console.error(error);
    res.statusCode = 500;
    res.setHeader("content-type", "application/json; charset=utf-8");
    res.end(JSON.stringify({ error: "internal_error" }));
  }
});

server.listen(port, "127.0.0.1", () => {
  console.log(`BYDMate Alice reference bridge listening on http://127.0.0.1:${port}`);
});
