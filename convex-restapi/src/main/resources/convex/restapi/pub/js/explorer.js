// Load saved theme on page load
function loadTheme() {
    const theme = localStorage.getItem('theme') || (window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark');
    document.documentElement.setAttribute('data-theme', theme);
    const toggleIcon = document.getElementById('theme-toggle-icon');
    if (toggleIcon) {
        toggleIcon.textContent = theme === 'light' ? '🌙' : '☀️';
        toggleIcon.setAttribute('aria-label', theme === 'light' ? 'Switch to dark mode' : 'Switch to light mode');
    }
}

// Save theme preference
function saveTheme(theme) {
    localStorage.setItem('theme', theme);
    document.documentElement.setAttribute('data-theme', theme);
    const toggleIcon = document.getElementById('theme-toggle-icon');
    if (toggleIcon) {
        toggleIcon.textContent = theme === 'light' ? '🌙' : '☀️';
        toggleIcon.setAttribute('aria-label', theme === 'light' ? 'Switch to dark mode' : 'Switch to light mode');
    }
}

// Toggle theme on icon click
function toggleTheme() {
    const currentTheme = document.documentElement.getAttribute('data-theme') || 'dark';
    const newTheme = currentTheme === 'light' ? 'dark' : 'light';
    saveTheme(newTheme);
}

// Initialize theme on page load
document.addEventListener('DOMContentLoaded', loadTheme);