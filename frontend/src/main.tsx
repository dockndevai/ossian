import React from "react";
import ReactDOM from "react-dom/client";
import { AuthProvider } from "react-oidc-context";
import { BrowserRouter } from "react-router-dom";
import { oidcConfig } from "./auth/oidc";
import { ThemeProvider } from "./app/ThemeContext";
import App from "./App";
import "./styles.css";

ReactDOM.createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    {/* Outside AuthProvider: the sign-in screen is rendered before anyone is authenticated,
        and it should honour the viewer's theme too. */}
    <ThemeProvider>
      <AuthProvider {...oidcConfig}>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </AuthProvider>
    </ThemeProvider>
  </React.StrictMode>,
);
