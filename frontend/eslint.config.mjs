import { defineConfig, globalIgnores } from "eslint/config";
import nextVitals from "eslint-config-next/core-web-vitals";
import nextTs from "eslint-config-next/typescript";
import tseslint from "typescript-eslint";

export default defineConfig([
  ...nextVitals,
  ...nextTs,
  {
    files: ["**/*.ts", "**/*.tsx"],
    plugins: { "@typescript-eslint": tseslint.plugin },
    rules: {
      // Mặc định của rule theo tài liệu typescript-eslint cho các định danh repo
      // tự đặt (default camelCase, variable camelCase/UPPER_CASE, typeLike
      // PascalCase). Hai nới theo quy ước framework và từ vựng ngoài:
      // - function/variable nhận thêm PascalCase: component và context của React
      //   bắt buộc viết hoa chữ đầu (React — Your First Component).
      // - thuộc tính object/type không kiểm format: khoá ở đó mô phỏng từ vựng do
      //   giao thức áp đặt (header HTTP, field OIDC, mã lỗi/enum của backend,
      //   biến môi trường), không thuộc quyền đặt tên của repo.
      "@typescript-eslint/naming-convention": [
        "error",
        {
          selector: "default",
          format: ["camelCase"],
          leadingUnderscore: "allow",
          trailingUnderscore: "allow",
        },
        { selector: "import", format: ["camelCase", "PascalCase"] },
        {
          selector: "variable",
          format: ["camelCase", "PascalCase", "UPPER_CASE"],
          leadingUnderscore: "allow",
          trailingUnderscore: "allow",
        },
        { selector: "function", format: ["camelCase", "PascalCase"] },
        { selector: "typeLike", format: ["PascalCase"] },
        {
          selector: ["objectLiteralProperty", "objectLiteralMethod", "typeProperty"],
          format: null,
        },
      ],
    },
  },
  globalIgnores([".next/**", "out/**", "build/**", "next-env.d.ts", "playwright-report/**", "test-results/**"]),
]);
