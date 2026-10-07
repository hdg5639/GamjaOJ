import './styles.css';
import './product-ui.css';
import './dark.css';
import './appearance.css';
import './select-control.css';
import './visual-motion.css';
import './surface-theme.css';
import './problem-statement.css';
import './auth-entry.css';

export const metadata = {
  title: 'GamjaOJ · 나의 알고리즘 연습장',
  description: '각자의 속도로, 함께 쌓아가는 알고리즘 연습장',
  icons: {
    icon: [{ url: '/gamjaoj-favicon.svg?v=hex-check-v1', type: 'image/svg+xml' }, { url: '/favicon-32x32.png?v=hex-check-v1', sizes: '32x32', type: 'image/png' },
      { url: '/favicon-16x16.png?v=hex-check-v1', sizes: '16x16', type: 'image/png' }, { url: '/favicon.ico?v=hex-check-v1', sizes: 'any' }],
    apple: '/apple-touch-icon.png?v=hex-check-v1',
  },
  manifest: '/site.webmanifest?v=hex-check-v1',
};

export default function Layout({ children }) {
  // theme.js runs synchronously before paint, so the saved or system theme never flashes the other one.
  return <html lang="ko" suppressHydrationWarning><head><script src="/theme.js" /><script src="/appearance.js" /></head><body>{children}</body></html>;
}
