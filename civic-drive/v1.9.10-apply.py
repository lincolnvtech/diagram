#!/usr/bin/env python3
"""Civic G8 1.9.10: remove white fuel artwork and make gear estimate more responsive."""
from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "build-civic")

def replace_exact(relative, old, new):
    f = root / relative
    data = f.read_text(encoding="utf-8")
    hits = data.count(old)
    if hits != 1:
        raise RuntimeError(f"{relative}: expected 1 occurrence, found {hits}: {old[:90]!r}")
    f.write_text(data.replace(old, new, 1), encoding="utf-8")

replace_exact("app/build.gradle", "versionCode 19\n        versionName '1.9.9'",
              "versionCode 20\n        versionName '1.9.10'")

dash = "app/src/main/java/br/com/civicdrive/DashboardActivity.java"
replace_exact(dash,
    '            if(car!=null){set(Color.WHITE);p.setAlpha(210);c.drawBitmap(car,null,new RectF(670,513,979,634),p);p.setAlpha(255);}',
    '''            // No bitmap over the dial: some head units render the artwork as a white rectangle.
            // Show explicit status rather than a blank white box.
            String autonomyStatus=d.fuelLitres<0f?"INFORME LITROS PARA AUTONOMIA":
                d.avg<=0f?"AGUARDANDO CONSUMO DO MOTOR":"AUTONOMIA APROXIMADA";
            label(c,autonomyStatus,824,626,21,MUTED,false,true);''')
replace_exact(dash,
    '''            smallCard(c,54,305,380,144,"CONSUMO MÉDIO",reading(d.avg,1,"km/L"),"Estimativa · ELM327");
            smallCard(c,54,493,380,144,"CONSUMO INSTANTÂNEO",reading(d.rate,2,"L/h"),"Quando disponível");''',
    '''            smallCard(c,54,305,380,144,"CONSUMO MÉDIO",reading(d.avg,1,"km/L"),
                d.avg>=0f?"Média estimada do percurso":"Aguardando dados do motor");
            // At speed km/h divided by L/h gives an instantaneous km/L approximation.
            // At idle show L/h instead; do not divide by zero or invent ECU values.
            String instant=d.rate>0.05f && d.speed>3f
                ?reading(d.speed/d.rate,1,"km/L"):reading(d.rate,2,"L/h");
            smallCard(c,54,493,380,144,"CONSUMO INSTANTÂNEO",instant,
                d.rate>=0f?"Estimativa pela ECU":"Sem vazão 015E / MAF");''')

gear = "app/src/main/java/br/com/civicdrive/GearEstimator.java"
replace_exact(gear,
    '''        if (candidate != best) {
            candidate = best;
            repeated = 1;
            return new Result(0, 0, "CALCULANDO");
        }''',
    '''        if (candidate != best) {
            candidate = best;
            repeated = 1;
            // One reading is enough when the ratio is very close and well separated.
            // Otherwise keep the original second-sample safety confirmation.
            if (bestError <= 0.10d && secondError - bestError >= 0.14d)
                return new Result(best, confidence, "OK");
            return new Result(0, 0, "CALCULANDO");
        }''')

obd = "app/src/main/java/br/com/civicdrive/ObdManager.java"
replace_exact(obd,
    '''                int which=optionalRound%extraPids.length;
                if (ecuResponded && n%5==0) optionalRound++;
                if (ecuResponded && n%5==0 && (which!=1 || !directFuelSupported) && System.currentTimeMillis()>=retryExtrasAt[which]) {''',
    '''                int which=optionalRound%extraPids.length;
                // Probe fuel rate (015E) and MAF (0110) early, then resume slower
                // optional polling so RPM and speed remain responsive.
                boolean probeExtra=ecuResponded && (n<=1 || n%5==0);
                if (probeExtra) optionalRound++;
                if (probeExtra && (which!=1 || !directFuelSupported) && System.currentTimeMillis()>=retryExtrasAt[which]) {''')

replace_exact("app/src/main/java/br/com/civicdrive/MainActivity.java",
              'setTitle("Civic G8 1.9.9")', 'setTitle("Civic G8 1.9.10")')

print("Civic G8 1.9.10 adjustments applied cleanly.")
