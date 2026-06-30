---
name: trigger-cloud-agent
on:
  issues:
    types: [opened, labeled]

permissions:
  contents: read
  issues: read

safe-outputs:
  # Explicitly binds your secret token to clear the runtime engine check
  github-token: ${{ secrets.COPILOT_GITHUB_TOKEN }}

  assign-to-agent:
    name: "copilot"
    model: "auto"
    max: 1
    target: "triggering"
    base-branch: "main"
---


# Trigger Copilot Coding Agent

## Intent
Monitor incoming repository issues. If an issue is opened that contains a clear, actionable coding feature request or bug description, or if a human applies the label 'ready-for-copilot', hand the task over to the GitHub Copilot Cloud Agent.

## Steps to Execute
1. Check the context of the current issue (`github.event.issue`).
2. If the issue has the label 'ready-for-copilot' OR if your AI analysis determines that the issue is a well-scoped bug fix or a minor feature update:
    - Post a comment on the issue saying: "@copilot please fix this issue by creating a localized implementation plan and opening a pull request."
    - Add the assignee '@copilot' to the issue so the team knows the Cloud Agent has picked it up.
3. If the issue is too vague, do not trigger the agent. Instead, leave a polite comment asking the user for more technical implementation details.

## Allowed GitHub Actions / API Tools
- github.issues.createComment
- github.issues.addAssignees
- github.issues.addLabels