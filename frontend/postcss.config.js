/**
 * PostCSS configuration.
 * Required by Tailwind CSS to process @tailwind base/components/utilities
 * directives in src/index.css at build time.
 * autoprefixer adds vendor prefixes for cross-browser compatibility.
 */
export default {
  plugins: {
    tailwindcss: {},
    autoprefixer: {},
  },
}
