// The data golden tests take a few seconds on wasm; Mocha's default timeout is 2 s.
config.set({ client: { mocha: { timeout: 30000 } }, browserNoActivityTimeout: 120000 });
