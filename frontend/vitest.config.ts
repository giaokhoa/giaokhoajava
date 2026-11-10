import path from "node:path";
import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

const root = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  resolve: {
    // Khớp "paths" trong tsconfig.json: test phải resolve alias "@/..." về
    // cùng thư mục src mà Next dùng, nếu không import trong src sẽ gãy ở test.
    alias: { "@": path.join(root, "src") },
  },
});
