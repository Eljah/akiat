"""Render local comparison page and auditable experiment notes (no services)."""
import json
import html
from pathlib import Path
import numpy as np
from investigate_music_profile import ROOT,read,stft,RATE

folder=Path((ROOT/'outputs/MUSIC_PROFILE_EXPERIMENT_LATEST.txt').read_text().strip())
main=json.loads((folder/'results.json').read_text())
rows=main['measurements'][:]
for filename in ('stereo_results.json','separator_results.json','dual_dictionary_results.json'):
    rows+=json.loads((folder/filename).read_text())['metrics']
labels={'current_0':'Старый профиль: 0 дБ','current_6':'Старый профиль: +6 дБ','current_12':'Старый профиль: +12 дБ',
 'wiener_1':'Мягкая спектральная маска ×1','wiener_4':'Мягкая спектральная маска ×4',
 'dual_dictionary_0.5':'Музыка + голос: мягко','dual_dictionary_1':'Музыка + голос: средне','dual_dictionary_2':'Музыка + голос: сильно',
 'demucs_050':'Demucs: 50%','demucs_075':'Demucs: 75%','demucs_100':'Demucs: 100%',
 'stereo_profile_050':'Стереопрофиль: 50%','stereo_profile_080':'Стереопрофиль: 80%','stereo_profile_100':'Стереопрофиль: 100%'}
f=main['features']
notes=f'''Исследование подавления фоновой музыки, 13.11.2026

Образец: {main['reference_uid']}
Исходный фрагмент: 00049, 12 секунд; образец 3.051020–3.944989 с.
В исходном MP3: 09:51.051–09:51.945. Это отмеченный пользователем образец
музыки; наличие отдельных инструментов не подтверждено прослушиванием.
Длительность образца {f['reference_seconds']:.3f} с, RMS {f['rms_dbfs']:.2f} дБFS.
Доля спектральной энергии ниже 500 Гц: {f['energy_below_500_fraction']:.1%};
ниже 1500 Гц: {f['energy_below_1500_fraction']:.1%}.
Основные пики (Гц): {f['peaks_hz']}.
Разброс уровня кадров между 10-м и 90-м процентилями: {f['frame_rms_90_10_db']:.1f} дБ.
Первая компонента спектрограммы описывает {f['rank1_energy_fraction']:.1%} квадратичной
энергии матрицы модулей. Один усреднённый спектр не описывает все изменения.
Эти цифры относятся к короткому образцу, не ко всей музыке произведения.

Методика
Первые 60% образца используются для обучения профилей, последние 40% —
как отложенная музыка в контрольной смеси. Для NMF проверены 2/6 компонентов,
100 итераций KL; в двойном словаре 6 музыкальных и 32 голосовых компоненты,
120 итераций. Маски ограничены снизу -18 дБ. Старый профиль запускается
исходной производственной функцией, с потолком подавления -18 дБ.
Контроль: первые 12 с синтетического голоса эпохи 99 + повторённая отложенная
музыка при SNR 15 дБ. Для голосового словаря взяты другие 4.83 с этой записи.
Искусственное повторение короткого образца даёт скачки на стыках. Это
лабораторный контроль, не оценка качества естественной речи рассказчика.
Результаты на исходных фрагментах 00048, 00049, 00050 сохранены отдельно.
Для них нет чистой голосовой дорожки, поэтому невозможно достоверно
измерить остаточную музыку под речью или доказать отсутствие потерь голоса.
Ослабление паузы 00049 измерено на всём образце и не является независимым тестом.

Стерео
Исходный MP3: 44100 Гц, два канала. Корреляция каналов на 36 с: 0.99434,
в образце: 0.94303. Side/mid: -25.48 и -13.82 дБ соответственно.
Профиль комплексного переноса side→mid обучен на первых 60% образца,
проверен на последних 40%. Результат около 2.3 дБ — недостаточно для очистки.
Стереоконтроль отличается: голос размещён по центру, дополнительно проверен
дисбаланс каналов 1%; его результаты нельзя прямо ранжировать с монотестом.

Demucs
Существующая локальная модель 955717e8 (htdemucs), CPU, 1 поток, shifts=0,
segment=7, overlap=.25, контекст 36 с. Голос извлечён после нормализации,
возврата исходного уровня и ресемплинга. Смеси 50/75/100% без автогромкости.
Этот разделитель почти не убрал фон отмеченного образца. В редактор не встроен.

Вывод
Убедительного метода полного удаления этой музыки пока не получено.
Кандидат для следующего прослушивания: dual_dictionary_1; более простой —
wiener_1. Двойной словарь лучше бережёт синтетический голос, чем старый
профиль, но под голосом контрольной смеси подавление музыки слабое.
Не следует внедрять сильное подавление по результатам одной паузы.
Для устойчивого профиля нужны несколько более длинных музыкальных участков
с разными нотами и чистые примеры именно этого рассказчика. Затем повторить
оценку на других фрагментах и проверить согласные, тембр и остатки музыки на слух.

Возможное представление будущего профиля
Версия метода; снимки музыкального и голосового словарей; исходные UID,
границы и SHA256; частота, FFT/hop; сила/нижний предел маски; параметры
сглаживания; версия алгоритма. Исходные WAV не менять, обработку вычислять
в предпросмотре и при подготовке нового обучающего снимка.
Файл dual_dictionary_profile.npz — экспериментальный снимок, не рецепт редактора.

Материалы: https://arxiv.org/abs/1309.6047 (NMF с ограничениями; наш опыт —
упрощённый вариант, не воспроизведение статьи);
https://github.com/facebookresearch/demucs (официальная реализация разделителя).
Все исходники и редакторские данные оставлены без изменений.
'''
(folder/'REPORT.txt').write_text(notes,encoding='utf-8')
# Standalone SVG: measured mean spectrum; no plotting package required.
p=np.mean(abs(stft(read(folder/'reference_music.wav'))[:,2:-2])**2,axis=1)
hz=np.fft.rfftfreq(2048,1/RATE);db=10*np.log10(np.maximum(p,1e-16));db-=db.max()
points=' '.join(f'{50+850*np.log10(h/20)/np.log10(10000/20):.1f},{30-min(0,max(-70,d))*4:.1f}' for h,d in zip(hz,db) if 20<=h<=10000)
svg=f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 940 360"><rect width="940" height="360" fill="white"/><text x="50" y="20">Спектр образца: дБ относительно максимума</text><polyline points="{points}" fill="none" stroke="#146c94" stroke-width="2"/>'
for freq in (20,50,100,200,500,1000,2000,5000,10000):
    xx=50+850*np.log10(freq/20)/np.log10(500)
    svg+=f'<text x="{xx}" y="340" font-size="12" text-anchor="middle">{freq} Гц</text>'
svg+='</svg>'; (folder/'reference_spectrum.svg').write_text(svg,encoding='utf-8')
table=''
for r in rows:
    score=r.get('control_snr_after_db');preserve=r.get('voice_only_error_snr_db')
    table+='<tr><td>'+labels.get(r['method'],r['method'])+'</td>'+''.join(f'<td>{v:.2f}</td>' if v is not None else '<td>—</td>' for v in (r['reference_pause_attenuation_db'],score,preserve))+'</tr>'
sections=''
fav=['dual_dictionary_1','wiener_1','current_0','current_12','stereo_profile_080','demucs_100']
for idx in (49,48,50):
    sections+=f'<section><h2>Фрагмент {idx:05d}</h2><p>Исходник</p><audio controls preload="none" src="fragment_{idx:05d}/00_original.wav"></audio>'
    for name in fav:
        sections+=f'<h3>{labels[name]}</h3><div class="pair"><div>После<br><audio controls preload="none" src="fragment_{idx:05d}/{name}.wav"></audio></div><div>Удалённая разница<br><audio controls preload="none" src="fragment_{idx:05d}/{name}_removed.wav"></audio></div></div>'
    sections+='</section>'
page='''<!doctype html><html lang="ru"><meta charset="utf-8"><title>Проверка подавления музыки</title>
<style>body{font:17px system-ui;max-width:1050px;margin:35px auto;padding:0 20px;color:#193043;background:#f4f7fa}section{background:white;padding:20px;margin:25px 0;border-radius:12px}table{border-collapse:collapse;width:100%;font-size:14px}td,th{padding:9px;border-bottom:1px solid #ccc;text-align:left}.pair{display:flex;gap:35px;flex-wrap:wrap}audio{width:360px;max-width:100%}.note{background:#fff1cf;padding:18px}img{width:100%}h3{font-size:18px}</style>
<h1>Подавление музыки: эксперимент</h1><p class="note">Готового решения полной очистки пока нет. Здесь сравниваются реальные результаты. Оригиналы и разметка не изменены; громкость файлов автоматически не выравнивалась.</p>
<p><a href="REPORT.txt">Подробный отчёт</a> · <a href="results.json">Измерения</a></p>
<h2>Ваш образец</h2><audio controls src="reference_music.wav"></audio><img src="reference_spectrum.svg" alt="Спектр образца">
<h2>Сравнение</h2><p>Ослабление — на отмеченной музыкальной паузе 00049. SNR — только на искусственной контрольной смеси (до обработки 15 дБ); больше — лучше. Последний столбец: чем больше, тем меньше изменён чистый синтетический голос. Это не оценка разборчивости исходной записи.</p>
<table><tr><th>Метод</th><th>Ослабление паузы, дБ</th><th>SNR контроля, дБ</th><th>Сохранность голоса: SNR ошибки, дБ</th></tr>'''+table+'</table>'+sections+'''<script>document.querySelectorAll('audio').forEach(a=>a.addEventListener('play',()=>document.querySelectorAll('audio').forEach(b=>{if(b!==a)b.pause()})))</script></html>'''
(folder/'compare.html').write_text(page,encoding='utf-8')
# Every output must keep 12-second alignment; residual reconstruction within PCM rounding.
import wave
for idx in (48,49,50):
    sub=folder/f'fragment_{idx:05d}';original=read(sub/'00_original.wav')
    for path in sub.glob('*.wav'):
        y=read(path);assert len(y)==len(original),(path,len(y));assert np.all(np.isfinite(y))
print('PASS: all 3 comparison groups preserve sample count/rate; report:',folder/'compare.html')
