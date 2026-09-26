import './styles.css';
import './product-ui.css';

export const metadata = {
  title: 'GamjaOJ · 나의 알고리즘 연습장',
  description: '각자의 속도로, 함께 쌓아가는 알고리즘 연습장',
};

export default function Layout({ children }) {
  return <html lang="ko"><body>{children}</body></html>;
}
