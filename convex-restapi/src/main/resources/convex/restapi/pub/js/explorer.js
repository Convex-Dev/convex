const THEME_KEY='convex-explorer-colour-theme';

// Load saved theme on page load
function loadTheme() {
    const theme = localStorage.getItem(THEME_KEY) || (window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark');
    document.documentElement.setAttribute('data-theme', theme);
}

// Save theme preference
function saveTheme(theme) {
    localStorage.setItem(THEME_KEY, theme);
    document.documentElement.setAttribute('data-theme', theme);
}

// Toggle theme on icon click
function toggleTheme() {
    const currentTheme = document.documentElement.getAttribute('data-theme') || 'dark';
    const newTheme = currentTheme === 'light' ? 'dark' : 'light';
    saveTheme(newTheme);
}

// Initialize theme on page load
document.addEventListener('DOMContentLoaded', loadTheme);