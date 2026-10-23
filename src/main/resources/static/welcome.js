/* 灯下欢迎页：保留双页翻动效果，不请求 API，也不修改会话存储。 */
(() => {
    'use strict';
    const body = document.body;
    const motion = document.querySelector('#motion');
    // One continuous sheet, with both hinge points fixed at the spine.
    const leaf=document.querySelector('.turning-page');
    const leafPaper=document.querySelector('#leaf-paper');
    const leafLines=document.querySelector('#leaf-lines');
    const leafEdge=document.querySelector('#leaf-edge');
    const reducedMotion=window.matchMedia('(prefers-reduced-motion: reduce)');
    let leafFrame=0;
    function stopPageTurn(){cancelAnimationFrame(leafFrame);leafFrame=0;leaf.setAttribute('visibility','hidden');}
    function paintPageTurn(progress){
      const eased=progress*progress*progress*(progress*(progress*6-15)+10);
      const angle=Math.PI*eased, swing=Math.cos(angle), lift=Math.sin(angle);
      function point(x,y){
        const u=(x-520)/210;
        const curl=Math.sin(Math.PI*u)*lift;
        return (520+(x-520)*swing+curl*13).toFixed(3)+' '+(y-lift*u*108-curl*15).toFixed(3);
      }
      leafPaper.setAttribute('d','M'+point(520,578)+'C'+point(585,548)+' '+point(658,537)+' '+point(730,552)+'L'+point(730,662)+'C'+point(658,649)+' '+point(585,660)+' '+point(520,690)+'Z');
      leafPaper.setAttribute('fill',swing>=0?'url(#rightPaper)':'url(#leftPaper)');
      leafEdge.setAttribute('d','M'+point(520,578)+'C'+point(585,548)+' '+point(658,537)+' '+point(730,552));
      const lines=[[702,576,626,570,550,598],[702,587,626,581,550,609],[702,598,626,592,550,620],[702,609,638,604,583,621]];
      leafLines.setAttribute('d',lines.map(p=>'M'+point(p[0],p[1])+'Q'+point(p[2],p[3])+' '+point(p[4],p[5])).join(''));
    }
    function startPageTurn(){
      stopPageTurn();
      if(reducedMotion.matches||body.classList.contains('quiet'))return;
      paintPageTurn(0);
      leaf.style.opacity='1';leaf.setAttribute('visibility','visible');
      const startAt=performance.now()+1250,duration=2250,settle=180,gap=220,turns=2;
      const cycle=duration+settle+gap,total=turns*(duration+settle)+(turns-1)*gap;
      function frame(now){
        const elapsed=Math.max(0,now-startAt);
        if(elapsed>=total){stopPageTurn();return;}
        const pageElapsed=elapsed%cycle;
        paintPageTurn(Math.min(1,pageElapsed/duration));
        // Keep the paper opaque all the way across; blend only after it has landed.
        leaf.style.opacity=String(pageElapsed<=duration?1:Math.max(0,1-(pageElapsed-duration)/settle));
        leafFrame=requestAnimationFrame(frame);
      }
      leafFrame=requestAnimationFrame(frame);
    }

    function setQuiet(quiet) {
        body.classList.toggle('quiet', quiet);
        stopPageTurn();
        motion.setAttribute('aria-pressed', String(quiet));
        motion.textContent = quiet ? '开启动效' : '静心模式';
    }

    motion.addEventListener('click', () => {
        setQuiet(!body.classList.contains('quiet'));
    });

    const lamp = document.querySelector('.lamp-control');
    function toggleLamp() {
        const off = body.classList.toggle('unlit');
        lamp.setAttribute('aria-pressed', String(!off));
    }
    lamp.addEventListener('click', toggleLamp);
    lamp.addEventListener('keydown', event => {
        if (event.key === 'Enter' || event.key === ' ') {
            event.preventDefault();
            toggleLamp();
        }
    });

    document.querySelector('#replay').addEventListener('click', () => {
        body.classList.remove('unlit');
        setQuiet(false);
        lamp.setAttribute('aria-pressed', 'true');
        const elements = document.querySelectorAll(
            '.intro .eyebrow, .intro h1, .intro .subtitle, .entrance, .pull, .light, .formula'
        );
        elements.forEach(element => { element.style.animation = 'none'; });
        void body.offsetHeight;
        elements.forEach(element => { element.style.animation = ''; });
        startPageTurn();
    });

    reducedMotion.addEventListener('change', () => {
        if (reducedMotion.matches) stopPageTurn();
    });
    startPageTurn();
})();
