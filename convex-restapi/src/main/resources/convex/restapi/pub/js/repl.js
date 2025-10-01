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
        
		appendOutput(JSON.stringify(result), false);
        if (result.errorCode) {
            appendOutput(account + '> ' + code, 'ERROR [' + result.errorCode + ']: ' + JSON.stringify(result.value), true);
        } else {
            appendOutput(account + '> ' + code, '=> ' + JSON.stringify(result.value));
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
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
        e.preventDefault();
        executeCode();
    }
});

// Welcome message
appendOutput('Welcome to Convex REPL', 'Press Ctrl+Enter or click Execute to run code. Use the account selector to choose an account.');

