## Agent Workflow - Detailed View

```mermaid
%%{init: {'theme':'base', 'themeVariables': { 'primaryColor':'#4A90E2','primaryTextColor':'#fff','primaryBorderColor':'#2E5C8A','lineColor':'#5C6BC0','secondaryColor':'#7CB342','tertiaryColor':'#FFA726'}}}%%
flowchart LR
    Start([🔌 Plugin])
    
    subgraph Diagnostic["📊 Diagnostic Agent"]
        Canary[Canary Pods<br/>Logs/Metrics]
        Stable[Stable Pods<br/>Logs/Metrics]
    end
    
    subgraph Analysis["🧠 Analysis Agent"]
        Analyze[AI Evaluation]
    end
    
    subgraph Scoring["📈 Scoring Agent"]
        Score[Quality Check]
    end
    
    Return([📤 Return])
    
    subgraph Remediation["🛠️ Remediation Agent"]
        Check{Code<br/>Fixable?}
        PR[Create PR]
        Issue[Create Issue]
    end
    
    Start --> Diagnostic
    Canary & Stable --> Analysis
    Analyze --> Score
    Score -->|Retry| Analyze
    Score --> Return
    
    Score -.->|Async| Remediation
    Check -->|Yes| PR
    Check -->|No| Issue
    
    style Start fill:#4A90E2,stroke:#2E5C8A,stroke-width:2px,color:#fff
    style Diagnostic fill:#42A5F5,stroke:#1976D2,stroke-width:3px,color:#fff
    style Canary fill:#7CB342,stroke:#558B2F,stroke-width:2px,color:#fff
    style Stable fill:#7CB342,stroke:#558B2F,stroke-width:2px,color:#fff
    style Analysis fill:#EC407A,stroke:#C2185B,stroke-width:3px,color:#fff
    style Analyze fill:#F06292,stroke:#C2185B,stroke-width:2px,color:#fff
    style Scoring fill:#9C27B0,stroke:#6A1B9A,stroke-width:3px,color:#fff
    style Score fill:#BA68C8,stroke:#6A1B9A,stroke-width:2px,color:#fff
    style Return fill:#5C6BC0,stroke:#3949AB,stroke-width:2px,color:#fff
    style Remediation fill:#FFA726,stroke:#F57C00,stroke-width:3px,color:#fff
    style Check fill:#FFD54F,stroke:#FFA000,stroke-width:2px,color:#000
    style PR fill:#66BB6A,stroke:#388E3C,stroke-width:2px,color:#fff
    style Issue fill:#FF9800,stroke:#F57C00,stroke-width:2px,color:#fff