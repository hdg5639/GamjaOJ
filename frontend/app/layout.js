import './styles.css';
import './product-ui.css';
import './dark.css';
import './appearance.css';
import './select-control.css';
import './visual-motion.css';
import './surface-theme.css';

export const metadata = {
  title: 'GamjaOJ · 나의 알고리즘 연습장',
  description: '각자의 속도로, 함께 쌓아가는 알고리즘 연습장',
  icons: {
    icon: [{ url: '/gamjaoj-favicon.svg', type: 'image/svg+xml' }, { url: '/favicon-32x32.png', sizes: '32x32', type: 'image/png' },
      { url: '/favicon-16x16.png', sizes: '16x16', type: 'image/png' }, { url: '/favicon.ico', sizes: 'any' }],
    apple: '/apple-touch-icon.png',
  },
  manifest: '/site.webmanifest',
};

export default function Layout({ children }) {
  // theme.js runs synchronously before paint, so the saved or system theme never flashes the other one.
  return <html lang="ko" suppressHydrationWarning><head><script src="/theme.js" /><script src="/appearance.js" /></head><body>{children}</body></html>;
}
