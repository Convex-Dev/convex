// REPL functionality for Convex Explorer

const replOutput = document.getElementById('repl-output');
const replInput = document.getElementById('repl-input');
const replAccount = document.getElementById('repl-account');
const replExecute = document.getElementById('repl-execute');
const replClear = document.getElementById('repl-clear');

function appendOutput(prompt, result, isError = false) {
    const entry = document.createElement('div');
    entry.className = 'repl-entry';
    const promptLine = document.createElement('div');
    promptLine.className = 'repl-prompt';
    promptLine.textContent = prompt;
    entry.appendChild(promptLine);
    const resultLine = document.createElement('div');
    resultLine.className = isError ? 'repl-error' : 'repl-result';
    resultLine.textContent = result;
    entry.appendChild(resultLine);
    replOutput.appendChild(entry);
    replOutput.scrollTop = replOutput.scrollHeight;
}

function isBalanced(text) {
    // Check if parentheses, brackets, and braces are balanced
    let parenCount = 0;
    let bracketCount = 0;
    let braceCount = 0;
    let inString = false;
    let escape = false;
    
    for (let i = 0; i < text.length; i++) {
        const char = text[i];
        
        // Handle string literals
        if (char === '"' && !escape) {
            inString = !inString;
        }
        
        // Handle escape sequences
        if (char === '\\' && !escape) {
            escape = true;
            continue;
        } else {
            escape = false;
        }
        
        // Skip characters inside strings
        if (inString) continue;
        
        // Count brackets
        if (char === '(') parenCount++;
        else if (char === ')') parenCount--;
        else if (char === '[') bracketCount++;
        else if (char === ']') bracketCount--;
        else if (char === '{') braceCount++;
        else if (char === '}') braceCount--;
        
        // If any count goes negative, it's unbalanced
        if (parenCount < 0 || bracketCount < 0 || braceCount < 0) {
            return false;
        }
    }
    
    // All counts should be zero and not inside a string
    return parenCount === 0 && bracketCount === 0 && braceCount === 0 && !inString;
}

function isCursorAtEnd(textarea) {
    return textarea.selectionStart === textarea.value.length && 
           textarea.selectionEnd === textarea.value.length;
}

async function executeCode() {
    const code = replInput.value.trim();
    if (!code) return;
    
    const account = replAccount.value;
    appendOutput("#" + account + ' > ' + code, 'Executing...');
    
    try {
        const response = await fetch('/api/v1/query', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
            body: JSON.stringify({ address: account, source: code })
        });
        
        const result = await response.json();
        
        // Remove 'Executing...' message
        replOutput.removeChild(replOutput.lastChild);
        
		// appendOutput(JSON.stringify(result), false);
        if (result.errorCode) {
            appendOutput(account + '> ' + code, 'ERROR [' + result.errorCode + ']: ' + result.value, true);
        } else {
            appendOutput(account + '> ' + code, '=> ' + result.result);
        }
    } catch (error) {
        replOutput.removeChild(replOutput.lastChild);
        appendOutput(account + '> ' + code, 'ERROR: ' + error.message, true);
    }
    
    replInput.value = '';
}

replExecute.addEventListener('click', executeCode);
replClear.addEventListener('click', () => { replOutput.innerHTML = ''; });

replInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
        // Ctrl+Enter or Cmd+Enter always executes
        if (e.ctrlKey || e.metaKey) {
            e.preventDefault();
            executeCode();
            return;
        }
        
        // Plain Enter executes if cursor is at end and parens are balanced
        // Shift+Enter always adds a new line (default behavior)
        if (!e.shiftKey && isCursorAtEnd(replInput) && isBalanced(replInput.value)) {
            e.preventDefault();
            executeCode();
        }
        // Otherwise, allow default behavior (new line)
    }
});

// Welcome message
appendOutput('Welcome to Convex REPL', 'Press Enter to execute (when cursor at end and parens balanced), Shift+Enter for new line, or Ctrl+Enter to force execute.');

