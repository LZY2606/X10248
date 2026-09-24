package pmx;

import org.junit.jupiter.api.Test;
import static pmx.LinAlg.*;

public class ScratchMathTest {
    @Test
    void scratch() {
        double[][] id = identity(4);
        double[] e = hermEig(Polar.coherency(id));
        System.out.println("coherency eig(I) ascending = " + java.util.Arrays.toString(e));
        // physical retarder: Mueller for linear retarder diag(1,1,cos,cos) style rotator
        double delta = Math.toRadians(60), c = Math.cos(delta), s = Math.sin(delta);
        double[][] mr = {
            {1,0,0,0},{0,1,0,0},{0,0,c,s},{0,0,-s,c}
        };
        double[] er = hermEig(Polar.coherency(mr));
        System.out.println("coherency eig(retarder) = " + java.util.Arrays.toString(er));
        Polar.Physicality ph = Polar.assessPhysicality(mr);
        System.out.println("psd="+ph.psd+" passive="+ph.passive+" viol="+ph.violations);
        // ideal depolarizer
        double[][] dep = {{1,0,0,0},{0,0,0,0},{0,0,0,0},{0,0,0,0}};
        double[] ed = hermEig(Polar.coherency(dep));
        System.out.println("coherency eig(depol) = " + java.util.Arrays.toString(ed));
        Polar.LuChipman lc = Polar.luChipman(mr);
        System.out.println("lu avail="+lc.available+" retard="+lc.retardance+" depPow="+lc.depolarizationPower
            + (lc.available ? "" : " diag="+lc.diagnostic));
        if (lc.available) {
            double[][] recon = mul(lc.mDepolarizer, mul(lc.mRetarder, lc.mDiattenuator));
            double err = 0; for (int i=0;i<4;i++) for(int j=0;j<4;j++) err += Math.abs(recon[i][j]-mr[i][j]);
            System.out.println("lu recon err="+err);
        }
        var comps = Polar.cloudeSpectral(mr);
        double[][] sum = mat(4,4);
        for (var cc : comps) for (int i=0;i<4;i++) for(int j=0;j<4;j++) sum[i][j]+=cc.m[i][j];
        double err=0; for(int i=0;i<4;i++) for(int j=0;j<4;j++) err+=Math.abs(sum[i][j]-mr[i][j]);
        System.out.println("cloude recon err="+err+" lambdas="+
            comps.stream().map(x->fmt(x.lambda)).toList());
    }
}
