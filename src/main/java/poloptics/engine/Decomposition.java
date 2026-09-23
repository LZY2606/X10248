package poloptics.engine;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lu-Chipman polar decomposition M = M_Delta M_R M_D (depolarizer, retarder,
 * diattenuator), with polarizance/diattenuation summaries.
 *
 * Multiple decomposition *candidates* are retained whenever the data supports
 * more than one branch (e.g. retardance sign ambiguity under a singular
 * design). Each candidate carries its own validity flag and residual.
 */
public final class Decomposition {
    private Decomposition() {}

    public static final class Candidate {
        public final String candidateId;
        public final boolean computable;
        public final Map<String, Double> scalars = new LinkedHashMap<>();
        public final double[][] depolarizer;
        public final double[][] retarder;
        public final double[][] diattenuator;
        public final double residual;
        public final String note;

        Candidate(String id, boolean ok, double[][] dep, double[][] ret, double[][] dia, double res, String note) {
            this.candidateId = id; this.computable = ok;
            this.depolarizer = dep; this.retarder = ret; this.diattenuator = dia;
            this.residual = res; this.note = note;
        }
    }

    public static Candidate luChipman(double[][] m) {
        double[] dvec = new double[3];
        for (int i = 0; i < 3; i++) dvec[i] = m[0][i+1] / Math.max(m[0][0], 1e-30);
        double d = Math.sqrt(dvec[0]*dvec[0]+dvec[1]*dvec[1]+dvec[2]*dvec[2]);
        if (d >= 0.999999) {
            return new Candidate("LUCHIPMAN_PRIMARY", false, null, null, null,
                    Double.NaN, "二向色性 |d|>=1，Lu-Chipman 分解在该样本上不可计算，保留候选占位而不伪造数值。");
        }
        double ts = Math.sqrt(Math.max(1 - d*d, 0));
        // diattenuator construction per Lu & Chipman (1996)
        double a = ts*ts;
        double b = (1-ts);
        double[][] md = {
            {1, dvec[0], dvec[1], dvec[2]},
            {dvec[0], b*dvec[0]*dvec[0]+a, b*dvec[0]*dvec[1], b*dvec[0]*dvec[2]},
            {dvec[1], b*dvec[0]*dvec[1], b*dvec[1]*dvec[1]+a, b*dvec[1]*dvec[2]},
            {dvec[2], b*dvec[0]*dvec[2], b*dvec[1]*dvec[2], b*dvec[2]*dvec[2]+a}
        };
        double[][] mdInv = Mat.inverse(md);
        double[][] mDeltaR = Mat.mul(m, mdInv);

        double[] pvec = new double[3];
        for (int i=0;i<3;i++) pvec[i] = mDeltaR[i+1][0];
        double[][] mR;
        double[][] mDelta;
        double retardance;
        try {
            // extract retardance from 3x3 block with polarizance removed
            double[][] q = new double[3][3];
            for (int i=0;i<3;i++) for (int j=0;j<3;j++) q[i][j]=mDeltaR[i+1][j+1];
            double[] pol = pvec.clone();
            // remove polarizance component (depolarizing Mueller construction)
            double[][] s = symmetric3(q, pol);
            double[][] n = new double[3][3];
            for (int i=0;i<3;i++) for(int j=0;j<3;j++) n[i][j]=q[i][j]-s[i][j];
            // retardance from antisymmetric part
            double[] rvec = new double[]{n[2][1]-n[1][2], n[0][2]-n[2][0], n[1][0]-n[0][1]};
            double rnorm = Math.sqrt(rvec[0]*rvec[0]+rvec[1]*rvec[1]+rvec[2]*rvec[2]);
            retardance = Math.asin(Math.min(1, Math.max(-1, rnorm/2.0)));

            mR = Mat.identity(4);
            for (int i=0;i<3;i++) for(int j=0;j<3;j++)
                mR[i+1][j+1] = rotationElement(i,j,rvec,retardance);
            mDelta = Mat.mul(mDeltaR, Mat.inverse(mR));
        } catch (RuntimeException ex) {
            return new Candidate("LUCHIPMAN_PRIMARY", false, null, null, null, Double.NaN,
                    "分解中间矩阵奇异：" + ex.getMessage());
        }

        double[] polEigen = symmetric3Eigenvalues(block3(mDelta));
        Candidate c = new Candidate("LUCHIPMAN_PRIMARY", true, mDelta, mR, md, 0,
                "Lu-Chipman 主候选；退偏特征值=" + fmt(polEigen));
        c.scalars.put("diattenuation", d);
        c.scalars.put("polarizance", Math.sqrt(pvec[0]*pvec[0]+pvec[1]*pvec[1]+pvec[2]*pvec[2]));
        c.scalars.put("retardance_rad", retardance);
        c.scalars.put("retardance_deg", Math.toDegrees(retardance));
        c.scalars.put("depolarization_index", depolarizationIndex(m, d));
        return c;
    }

    private static double rotationElement(int i, int j, double[] rvec, double t) {
        double rn = Math.sqrt(rvec[0]*rvec[0]+rvec[1]*rvec[1]+rvec[2]*rvec[2]);
        if (rn < 1e-15) return i==j?1:0;
        double[] u = {rvec[0]/rn, rvec[1]/rn, rvec[2]/rn};
        double c = Math.cos(t), s = Math.sin(t);
        return (i==j?c:0) + u[i]*u[j]*(1-c) + (i==j?0:s)*(new double[][]{{0,-u[2],u[1]},{u[2],0,-u[0]},{-u[1],u[0],0}})[i][j];
    }

    private static double[][] block3(double[][] a) {
        double[][] b = new double[3][3];
        for (int i=0;i<3;i++) for(int j=0;j<3;j++) b[i][j]=a[i+1][j+1];
        return b;
    }

    private static double[][] symmetric3(double[][] q, double[] pol) {
        // symmetric part of 3x3 used for depolarizer
        double[][] s = new double[3][3];
        for (int i=0;i<3;i++) for(int j=0;j<3;j++) s[i][j]=(q[i][j]+q[j][i])/2;
        return s;
    }

    private static double[] symmetric3Eigenvalues(double[][] a) {
        double[][] h = new double[3][3];
        for (int i=0;i<3;i++) System.arraycopy(a[i],0,h[i],0,3);
        double[] d = new double[3];
        for (int sweep=0;sweep<100;sweep++){
            double off=0; for(int p=0;p<3;p++)for(int q=p+1;q<3;q++) off+=h[p][q]*h[p][q];
            if(off<1e-24) break;
            for(int p=0;p<3;p++)for(int q=p+1;q<3;q++){
                if(Math.abs(h[p][q])<1e-18) continue;
                double tau=(h[q][q]-h[p][p])/(2*h[p][q]);
                double t=(tau>=0?1:-1)/(Math.abs(tau)+Math.sqrt(1+tau*tau));
                double c=1/Math.sqrt(1+t*t), s=t*c;
                double app=h[p][p],aqq=h[q][q],apq=h[p][q];
                h[p][p]=app-t*apq;h[q][q]=aqq+t*apq;h[p][q]=h[q][p]=0;
                for(int k=0;k<3;k++)if(k!=p&&k!=q){double akp=h[k][p],akq=h[k][q];h[k][p]=h[p][k]=c*akp-s*akq;h[k][q]=h[q][k]=s*akp+c*akq;}
            }
        }
        for(int i=0;i<3;i++) d[i]=h[i][i];
        return d;
    }

    /** Standard depolarization index DI = sqrt(sum_{(i,j)!=(0,0)} mij^2) / (sqrt(3) m00). */
    private static double depolarizationIndex(double[][] m, double diatt) {
        double sum = 0;
        for (int i=0;i<4;i++) for(int j=0;j<4;j++)
            if (!(i==0&&j==0)) sum += m[i][j]*m[i][j];
        double denom = Math.sqrt(3.0)*Math.abs(m[0][0]);
        return denom > 1e-30 ? Math.sqrt(sum)/denom : Double.NaN;
    }

    private static String fmt(double[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i=0;i<v.length;i++) sb.append(String.format("%.4f", v[i])).append(i<v.length-1?",":"");
        return sb.append("]").toString();
    }
}
