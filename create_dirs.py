import os

# Create all the directories
directories = [
    'algorithmic-art',
    'brand-guidelines',
    'canvas-design',
    'doc-coauthoring',
    'docx',
    'frontend-design',
    'internal-comms',
    'mcp-builder',
    'pdf',
    'pptx',
    'skill-creator',
    'slack-gif-creator',
    'theme-factory',
    'web-artifacts-builder'
]

for d in directories:
    path = f'C:/Users/dongh/.copilot/skills/{d}'
    os.makedirs(path, exist_ok=True)
    print(f'Created: {path}')

print('Done')
