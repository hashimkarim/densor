import type { Metadata } from 'next';
import './globals.css';
const title = 'Densor EEPROM Lab';
const description = 'Compare baseline, R1, R2 shared and R3 partitioned EEPROM page crossings avoided, alignment and multirate capacity.';
export const metadata: Metadata = {
  metadataBase: new URL(process.env.SITE_URL || 'http://localhost:8767'),
  openGraph: { title, description, images: [{ url: '/og.png', width: 1536, height: 1024, alt: 'Densor EEPROM Lab — Every byte has a story.' }] },
  twitter: { card: 'summary_large_image', title, description, images: ['/og.png'] },
  icons: { icon: '/favicon.png' },
  title: 'Densor EEPROM Lab',
  description: 'Explore baseline, R1, R2a/b shared and R3a/b partitioned EEPROM layouts, page alignment and multirate capacity.',
};
export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="en"><body>{children}</body></html>;
}
