import { readFile, writeFile } from "node:fs/promises";
import {
  argbFromHex,
  Hct,
  SchemeVibrant,
  hexFromArgb,
} from "@material/material-color-utilities";
const roles = [
  "primary",
  "onPrimary",
  "primaryContainer",
  "onPrimaryContainer",
  "secondaryContainer",
  "onSecondaryContainer",
  "error",
  "onErrorContainer",
  "errorContainer",
  "surface",
  "onSurface",
  "onSurfaceVariant",
  "surfaceContainerLowest",
  "surfaceContainerLow",
  "surfaceContainer",
  "surfaceContainerHigh",
  "outline",
  "outlineVariant",
  "inverseSurface",
  "inverseOnSurface",
];
let css =
  "/* Generated with Material Color Utilities 0.3.0. Copyright Google LLC. Apache-2.0. */\n";
for (const dark of [false, true]) {
  const s = new SchemeVibrant(Hct.fromInt(argbFromHex("#1a73e8")), dark, 0);
  css += `${dark ? ':root[data-theme="dark"]' : ":root"} {\n`;
  for (const role of roles)
    css += `  --md-sys-color-${role.replace(/[A-Z]/g, (c) => "-" + c.toLowerCase())}: ${hexFromArgb(s[role])};\n`;
  css += "}\n";
}
const file = new URL(
  "../src/main/resources/static/assets/material-tokens.css",
  import.meta.url,
);
if (process.argv.includes("--check")) {
  if ((await readFile(file, "utf8")) !== css)
    throw Error("Run npm run build:theme and commit the generated tokens.");
} else await writeFile(file, css);
