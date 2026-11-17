// Commit messages follow Conventional Commits; the allowed types are the
// ones documented in CONTRIBUTING.md.
export default {
  extends: ["@commitlint/config-conventional"],
  rules: {
    "type-enum": [
      2,
      "always",
      ["feat", "fix", "docs", "test", "refactor", "build", "ci", "chore"],
    ],
  },
};
