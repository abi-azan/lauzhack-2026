// The chat engine's golden test (railstatus.chat.EngineGoldenTest) parses 1.6 MB of JSON and runs ~3000
// answers in one go: longer than Karma's 2 s ping and Mocha's 2 s per-test timeout in a browser.
config.set({
  pingTimeout: 120000,
  browserNoActivityTimeout: 120000,
  browserDisconnectTimeout: 120000,
});
config.client = config.client || {};
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 120000 });
