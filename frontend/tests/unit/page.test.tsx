import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import Home from "../../src/app/page";

describe("Trang chủ", () => {
  it("dẫn đến endpoint đăng nhập của Spring, không tạo phiên riêng ở Next", () => {
    const html = renderToStaticMarkup(createElement(Home));

    expect(html).toContain('href="/oauth2/authorization/keycloak"');
  });
});
