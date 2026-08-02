export default {
  testEnvironment: "jsdom",
  transform: {
    "^.+\\.ts?$": ["ts-jest", { tsconfig: "tsconfig.test.json" }],
    "^.+\\.tsx?$": ["ts-jest", { tsconfig: "tsconfig.test.json" }],
    "^.+\\.jsx?$": ["ts-jest", { tsconfig: "tsconfig.test.json" }],
  },
  transformIgnorePatterns: ["/node_modules/(?!(@ant-design)/)"],
  moduleNameMapper: {
    "\\.(css|less|sass|scss)$": "identity-obj-proxy",
    "^.+\\.svg$": "jest-transformer-svg",
    "\\.(png|jpe?g|gif|webp)$": "<rootDir>/jest.fileMock.js",
    "^@/(.*)$": "<rootDir>/src/$1",
    // Some source files import relative TS/TSX modules with an explicit .js/.jsx
    // extension (a Vite/ESM-friendly convention). Jest's default resolver doesn't
    // strip that extension before looking for the real .ts/.tsx file, so map it off.
    "^(\\.{1,2}/.*)\\.jsx?$": "$1",
  },
  setupFilesAfterEnv: ["<rootDir>/jest.setup.ts"],
};
